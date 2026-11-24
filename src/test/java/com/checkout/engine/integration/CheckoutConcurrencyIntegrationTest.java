package com.checkout.engine.integration;

import com.checkout.engine.entity.Cart;
import com.checkout.engine.entity.CartItem;
import com.checkout.engine.entity.CartStatus;
import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.InventoryReservation;
import com.checkout.engine.entity.Product;
import com.checkout.engine.entity.ReservationStatus;
import com.checkout.engine.entity.User;
import com.checkout.engine.exception.InsufficientStockException;
import com.checkout.engine.exception.LockAcquisitionException;
import com.checkout.engine.repository.CartItemRepository;
import com.checkout.engine.repository.CartRepository;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.InventoryReservationRepository;
import com.checkout.engine.repository.ProductRepository;
import com.checkout.engine.repository.UserRepository;
import com.checkout.engine.service.CheckoutService;
import com.checkout.engine.service.lock.OptimisticLockStrategy;
import com.checkout.engine.service.lock.PessimisticDbLockStrategy;
import com.checkout.engine.service.lock.RedisDistributedLockStrategy;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
@ActiveProfiles("integration")
public class CheckoutConcurrencyIntegrationTest {

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private PessimisticDbLockStrategy pessimisticDbLockStrategy;

    @Autowired
    private RedisDistributedLockStrategy redisDistributedLockStrategy;

    @Autowired
    private OptimisticLockStrategy optimisticLockStrategy;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryReservationRepository inventoryReservationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("50 concurrent threads compete for 5 stock units with Pessimistic DB Locking -> exactly 5 succeed, 45 fail cleanly, 0 oversell")
    void testConcurrentCheckout_pessimisticLocking_preventsOverselling() throws Exception {
        ReflectionTestUtils.setField(checkoutService, "inventoryLockStrategy", pessimisticDbLockStrategy);
        runConcurrencyTest("pessimistic");
    }

    @Test
    @DisplayName("50 concurrent threads compete for 5 stock units with Redis Distributed Locking -> exactly 5 succeed, 45 fail cleanly, 0 oversell")
    void testConcurrentCheckout_redisDistributedLocking_preventsOverselling() throws Exception {
        ReflectionTestUtils.setField(checkoutService, "inventoryLockStrategy", redisDistributedLockStrategy);
        runConcurrencyTest("redis");
    }

    @Test
    @DisplayName("50 concurrent threads compete for 5 stock units with Optimistic Locking -> exactly 5 succeed, 45 fail cleanly, 0 oversell")
    void testConcurrentCheckout_optimisticLocking_preventsOverselling() throws Exception {
        ReflectionTestUtils.setField(checkoutService, "inventoryLockStrategy", optimisticLockStrategy);
        runConcurrencyTest("optimistic");
    }

    private void runConcurrencyTest(String strategyName) throws Exception {
        log.info("Starting 50-thread oversell concurrency integration test with strategy: {}", strategyName);

        // 1. Create a dedicated test product with exactly 5 available units
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        Product product = Product.builder()
                .sku("SKU-CONCUR-" + strategyName.toUpperCase() + "-" + uniqueSuffix)
                .name("Concurrency Test Product " + strategyName)
                .price(BigDecimal.valueOf(99.99))
                .status("ACTIVE")
                .build();
        Product savedProduct = productRepository.save(product);

        Inventory inventory = Inventory.builder()
                .product(savedProduct)
                .availableQty(5)
                .reservedQty(0)
                .build();
        inventoryRepository.save(inventory);

        // 2. Prepare 50 distinct users, each with an active cart containing 1 unit of the product
        int threadCount = 50;
        List<User> testUsers = new ArrayList<>();
        for (int i = 1; i <= threadCount; i++) {
            String email = "concur_" + strategyName + "_" + uniqueSuffix + "_" + i + "@example.com";
            User user = userRepository.save(User.builder()
                    .email(email)
                    .name("User " + i)
                    .build());
            testUsers.add(user);

            Cart cart = cartRepository.save(Cart.builder()
                    .user(user)
                    .status(CartStatus.ACTIVE)
                    .build());

            cartItemRepository.save(CartItem.builder()
                    .cart(cart)
                    .product(savedProduct)
                    .quantity(1)
                    .build());
        }

        // 3. Setup concurrency harness
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger cleanRejectionCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final Long userId = testUsers.get(i).getId();
            final String idempotencyKey = "chk-concur-" + strategyName + "-" + uniqueSuffix + "-" + i;

            executor.submit(() -> {
                try {
                    startGate.await();
                    checkoutService.checkout(userId, idempotencyKey);
                    successCount.incrementAndGet();
                } catch (InsufficientStockException | ObjectOptimisticLockingFailureException | OptimisticLockException | LockAcquisitionException e) {
                    cleanRejectionCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrorCount.incrementAndGet();
                    unexpectedErrors.add(t);
                    log.error("Unexpected error in thread for user {}: {}", userId, t.getMessage(), t);
                } finally {
                    finishGate.countDown();
                }
            });
        }

        // 4. Release all 50 threads simultaneously
        long startTime = System.currentTimeMillis();
        startGate.countDown();

        boolean finishedInTime = finishGate.await(45, TimeUnit.SECONDS);
        long durationMs = System.currentTimeMillis() - startTime;
        executor.shutdown();

        log.info("Strategy [{}] completed in {} ms: {} successes, {} clean rejections, {} unexpected errors",
                strategyName, durationMs, successCount.get(), cleanRejectionCount.get(), unexpectedErrorCount.get());

        // 5. Strict Assertions
        assertThat(finishedInTime).as("All 50 threads must complete within timeout").isTrue();
        assertThat(unexpectedErrors).as("Zero unexpected errors allowed").isEmpty();
        assertThat(successCount.get()).as("Exactly 5 checkouts must succeed").isEqualTo(5);
        assertThat(cleanRejectionCount.get()).as("Exactly 45 checkouts must fail cleanly without overselling").isEqualTo(45);

        // 6. DB State Verification: zero overselling
        Inventory finalInventory = inventoryRepository.findByProductId(savedProduct.getId()).orElseThrow();
        assertThat(finalInventory.getAvailableQty()).as("Final available_qty must be 0").isEqualTo(0);
        assertThat(finalInventory.getReservedQty()).as("Final reserved_qty must be 5").isEqualTo(5);

        List<InventoryReservation> activeReservations = inventoryReservationRepository
                .findByProductIdAndStatus(savedProduct.getId(), ReservationStatus.ACTIVE);
        assertThat(activeReservations).as("Exactly 5 ACTIVE reservations must exist").hasSize(5);
    }
}
