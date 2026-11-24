package com.checkout.engine.service.lock;

import com.checkout.engine.entity.Cart;
import com.checkout.engine.entity.CartItem;
import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.InventoryReservation;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.Product;
import com.checkout.engine.exception.InsufficientStockException;
import com.checkout.engine.exception.LockAcquisitionException;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.InventoryReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisDistributedLockStrategyTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private InventoryReservationRepository inventoryReservationRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisDistributedLockStrategy strategy;

    private Order sampleOrder;
    private Product productA;
    private Product productB;
    private Inventory inventoryA;
    private Inventory inventoryB;

    @BeforeEach
    void setUp() {
        strategy = new RedisDistributedLockStrategy(inventoryRepository, inventoryReservationRepository, redisTemplate);

        sampleOrder = Order.builder().id(100L).orderNumber("ORD-100").build();

        productA = Product.builder().id(3L).sku("PROD-3").price(BigDecimal.TEN).build();
        productB = Product.builder().id(7L).sku("PROD-7").price(BigDecimal.ONE).build();

        inventoryA = Inventory.builder().id(1L).product(productA).availableQty(10).reservedQty(0).build();
        inventoryB = Inventory.builder().id(2L).product(productB).availableQty(20).reservedQty(0).build();
    }

    @Test
    void executeWithDistributedLock_acquiresLocksInAscendingOrderAndReleasesWithLuaScript() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), any(), any())).thenReturn(1L);

        // Pass descending and duplicate product IDs: 7, 3, 7
        String result = strategy.executeWithDistributedLock(List.of(7L, 3L, 7L), () -> "ACTION_SUCCESS");

        assertThat(result).isEqualTo("ACTION_SUCCESS");

        // Verify lock acquisition order was strictly ascending and deduplicated (product 3 before product 7)
        InOrder inOrder = inOrder(valueOperations);
        inOrder.verify(valueOperations).setIfAbsent(eq("lock:inventory:product:3"), anyString(), any(Duration.class));
        inOrder.verify(valueOperations).setIfAbsent(eq("lock:inventory:product:7"), anyString(), any(Duration.class));

        // Verify atomic Lua unlock executed for both products in finally
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), any(), any());
    }

    @Test
    void executeWithDistributedLock_callbackThrowsException_releasesLocksInFinally() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThatThrownBy(() -> strategy.executeWithDistributedLock(List.of(3L), () -> {
            throw new RuntimeException("DB transaction failed");
        })).isInstanceOf(RuntimeException.class).hasMessage("DB transaction failed");

        // Verify Lua script unlock was still executed in finally block
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("lock:inventory:product:3")), any());
    }

    @Test
    void executeWithDistributedLock_lockAcquisitionTimeout_throwsLockAcquisitionExceptionAndReleasesAcquiredLocks() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Product 3 lock succeeds, Product 7 lock fails (returns false on timeout)
        when(valueOperations.setIfAbsent(eq("lock:inventory:product:3"), anyString(), any(Duration.class))).thenReturn(true);
        when(valueOperations.setIfAbsent(eq("lock:inventory:product:7"), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> strategy.executeWithDistributedLock(List.of(3L, 7L), () -> "OK"))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("Could not acquire inventory lock for product: 7");

        // Verify product 3's lock was released via atomic Lua script in finally block
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("lock:inventory:product:3")), any());
    }

    @Test
    void executeWithDistributedLock_nullRedisTemplate_throwsIllegalStateException() {
        RedisDistributedLockStrategy noRedisStrategy =
                new RedisDistributedLockStrategy(inventoryRepository, inventoryReservationRepository, null);

        assertThatThrownBy(() -> noRedisStrategy.executeWithDistributedLock(List.of(3L), () -> "OK"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis is not configured");
    }

    @Test
    void reserveInventory_performsPureDbCheckAndReservationWithoutRedisOperations() {
        when(inventoryRepository.findByProductId(3L)).thenReturn(Optional.of(inventoryA));
        when(inventoryRepository.findByProductId(7L)).thenReturn(Optional.of(inventoryB));
        when(inventoryReservationRepository.save(any(InventoryReservation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Cart cart = Cart.builder().id(10L).build();
        CartItem itemB = CartItem.builder().id(101L).cart(cart).product(productB).quantity(2).build();
        CartItem itemA = CartItem.builder().id(102L).cart(cart).product(productA).quantity(1).build();

        List<InventoryReservation> reservations = strategy.reserveInventory(sampleOrder, List.of(itemB, itemA));

        assertThat(reservations).hasSize(2);
        assertThat(inventoryA.getAvailableQty()).isEqualTo(9);
        assertThat(inventoryA.getReservedQty()).isEqualTo(1);
        assertThat(inventoryB.getAvailableQty()).isEqualTo(18);
        assertThat(inventoryB.getReservedQty()).isEqualTo(2);

        // Verify redisTemplate was never touched in reserveInventory
        verify(redisTemplate, never()).opsForValue();
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(), any());
    }

    @Test
    void reserveInventory_insufficientStock_throwsInsufficientStockException() {
        when(inventoryRepository.findByProductId(3L)).thenReturn(Optional.of(inventoryA));

        Cart cart = Cart.builder().id(10L).build();
        CartItem itemA = CartItem.builder().id(101L).cart(cart).product(productA).quantity(50).build();

        assertThatThrownBy(() -> strategy.reserveInventory(sampleOrder, List.of(itemA)))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(inventoryA.getAvailableQty()).isEqualTo(10);
    }
}
