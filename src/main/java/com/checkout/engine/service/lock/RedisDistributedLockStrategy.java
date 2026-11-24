package com.checkout.engine.service.lock;

import com.checkout.engine.entity.CartItem;
import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.InventoryReservation;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.Product;
import com.checkout.engine.entity.ReservationStatus;
import com.checkout.engine.exception.InsufficientStockException;
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.InventoryReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component("redisDistributedLockStrategy")
public class RedisDistributedLockStrategy implements InventoryLockStrategy {

    private static final String LOCK_KEY_PREFIX = "lock:inventory:product:";
    private static final Duration LOCK_LEASE_TIME = Duration.ofSeconds(10);
    private static final long LOCK_ACQUISITION_TIMEOUT_MS = 3000L;
    private static final long RETRY_INTERVAL_MS = 50L;

    private static final RedisScript<Long> UNLOCK_LUA_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end",
            Long.class
    );

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final StringRedisTemplate redisTemplate;

    public RedisDistributedLockStrategy(
            InventoryRepository inventoryRepository,
            InventoryReservationRepository inventoryReservationRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.inventoryRepository = inventoryRepository;
        this.inventoryReservationRepository = inventoryReservationRepository;
        this.redisTemplate = redisTemplate;
    }

    @Override
    @Transactional
    public List<InventoryReservation> reserveInventory(Order order, List<CartItem> cartItems) {
        if (redisTemplate == null) {
            throw new IllegalStateException("Redis is not configured or StringRedisTemplate is unavailable");
        }

        // Sort cart items by product ID in ascending order to prevent distributed deadlocks
        List<CartItem> sortedItems = cartItems.stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .toList();

        String lockOwnerToken = UUID.randomUUID().toString();
        List<String> acquiredLockKeys = new ArrayList<>();

        try {
            // Acquire Redis locks in ascending product order
            for (CartItem item : sortedItems) {
                String lockKey = LOCK_KEY_PREFIX + item.getProduct().getId();
                boolean acquired = acquireLockWithRetry(lockKey, lockOwnerToken, LOCK_LEASE_TIME, LOCK_ACQUISITION_TIMEOUT_MS);
                if (!acquired) {
                    log.error("Failed to acquire Redis distributed lock for key {} within {} ms",
                            lockKey, LOCK_ACQUISITION_TIMEOUT_MS);
                    throw new IllegalStateException("Could not acquire inventory lock for product: " + item.getProduct().getId());
                }
                acquiredLockKeys.add(lockKey);
            }

            // Perform DB update within protected critical section
            List<InventoryReservation> reservations = new ArrayList<>();
            Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);

            for (CartItem item : sortedItems) {
                Product product = item.getProduct();
                Long productId = product.getId();
                int requestedQty = item.getQuantity();

                Inventory inventory = inventoryRepository.findByProductId(productId)
                        .orElseThrow(() -> new ResourceNotFoundException("Inventory", "productId", productId));

                if (inventory.getAvailableQty() < requestedQty) {
                    log.warn("Insufficient stock for product {}. Requested: {}, Available: {}",
                            product.getSku(), requestedQty, inventory.getAvailableQty());
                    throw new InsufficientStockException(product.getSku(), requestedQty, inventory.getAvailableQty());
                }

                inventory.setAvailableQty(inventory.getAvailableQty() - requestedQty);
                inventory.setReservedQty(inventory.getReservedQty() + requestedQty);
                inventoryRepository.save(inventory);

                InventoryReservation reservation = InventoryReservation.builder()
                        .order(order)
                        .product(product)
                        .quantity(requestedQty)
                        .status(ReservationStatus.ACTIVE)
                        .expiresAt(expiresAt)
                        .build();

                reservations.add(inventoryReservationRepository.save(reservation));
                log.info("Reserved {} units of product {} for order {} under Redis distributed lock",
                        requestedQty, product.getSku(), order.getOrderNumber());
            }

            return reservations;

        } finally {
            // Atomically release all acquired Redis locks via Lua script validating owner token
            for (String lockKey : acquiredLockKeys) {
                releaseLock(lockKey, lockOwnerToken);
            }
        }
    }

    private boolean acquireLockWithRetry(String lockKey, String lockToken, Duration leaseTime, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, leaseTime);
            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Acquired Redis lock {} with token {}", lockKey, lockToken);
                return true;
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Redis lock: " + lockKey, e);
            }
        }
        return false;
    }

    private void releaseLock(String lockKey, String lockToken) {
        try {
            Long result = redisTemplate.execute(UNLOCK_LUA_SCRIPT, Collections.singletonList(lockKey), lockToken);
            if (Long.valueOf(1L).equals(result)) {
                log.debug("Successfully released Redis lock {} for token {}", lockKey, lockToken);
            } else {
                log.warn("Redis lock {} for token {} was not released (expired or held by another owner)", lockKey, lockToken);
            }
        } catch (Exception e) {
            log.error("Error executing atomic Lua unlock script for Redis lock key {}", lockKey, e);
        }
    }
}
