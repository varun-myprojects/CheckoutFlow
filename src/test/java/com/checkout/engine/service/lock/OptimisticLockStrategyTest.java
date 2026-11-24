package com.checkout.engine.service.lock;

import com.checkout.engine.entity.Cart;
import com.checkout.engine.entity.CartItem;
import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.InventoryReservation;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.Product;
import com.checkout.engine.entity.ReservationStatus;
import com.checkout.engine.entity.User;
import com.checkout.engine.exception.InsufficientStockException;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.InventoryReservationRepository;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptimisticLockStrategyTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private InventoryReservationRepository inventoryReservationRepository;

    @InjectMocks
    private OptimisticLockStrategy strategy;

    private Order sampleOrder;
    private Product sampleProduct;
    private Inventory sampleInventory;

    @BeforeEach
    void setUp() {
        User user = User.builder().id(1L).email("user@example.com").name("User").build();
        sampleOrder = Order.builder().id(100L).orderNumber("ORD-100").user(user).build();

        sampleProduct = Product.builder().id(5L).sku("PROD-5").price(BigDecimal.valueOf(100.00)).build();
        sampleInventory = Inventory.builder().id(1L).product(sampleProduct).availableQty(10).reservedQty(0).version(0L).build();
    }

    @Test
    void reserveInventory_happyPath_successOnFirstAttempt() {
        when(inventoryRepository.findByProductId(5L)).thenReturn(Optional.of(sampleInventory));
        when(inventoryRepository.saveAndFlush(any(Inventory.class))).thenReturn(sampleInventory);
        when(inventoryReservationRepository.save(any(InventoryReservation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Cart cart = Cart.builder().id(10L).build();
        CartItem item = CartItem.builder().id(101L).cart(cart).product(sampleProduct).quantity(3).build();

        List<InventoryReservation> reservations = strategy.reserveInventory(sampleOrder, List.of(item));

        assertThat(reservations).hasSize(1);
        assertThat(reservations.get(0).getQuantity()).isEqualTo(3);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(sampleInventory.getAvailableQty()).isEqualTo(7);
        assertThat(sampleInventory.getReservedQty()).isEqualTo(3);

        verify(inventoryRepository, times(1)).saveAndFlush(any(Inventory.class));
    }

    @Test
    void reserveInventory_insufficientStock_throwsImmediatelyWithoutRetry() {
        when(inventoryRepository.findByProductId(5L)).thenReturn(Optional.of(sampleInventory));

        Cart cart = Cart.builder().id(10L).build();
        CartItem item = CartItem.builder().id(101L).cart(cart).product(sampleProduct).quantity(20).build(); // 20 requested, 10 available

        assertThatThrownBy(() -> strategy.reserveInventory(sampleOrder, List.of(item)))
                .isInstanceOf(InsufficientStockException.class);

        verify(inventoryRepository, times(1)).findByProductId(5L);
        verify(inventoryRepository, times(0)).saveAndFlush(any());
    }

    @Test
    void reserveInventory_optimisticLockConflict_propagatesExceptionImmediatelyWithoutRetry() {
        when(inventoryRepository.findByProductId(5L)).thenReturn(Optional.of(sampleInventory));
        when(inventoryRepository.saveAndFlush(any(Inventory.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L));

        Cart cart = Cart.builder().id(10L).build();
        CartItem item = CartItem.builder().id(101L).cart(cart).product(sampleProduct).quantity(2).build();

        // Must propagate ObjectOptimisticLockingFailureException out immediately without inner retry loop
        assertThatThrownBy(() -> strategy.reserveInventory(sampleOrder, List.of(item)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(inventoryRepository, times(1)).saveAndFlush(any(Inventory.class));
    }

    @Test
    void reserveInventory_jpaOptimisticLockException_propagatesExceptionImmediatelyWithoutRetry() {
        when(inventoryRepository.findByProductId(5L)).thenReturn(Optional.of(sampleInventory));
        when(inventoryRepository.saveAndFlush(any(Inventory.class)))
                .thenThrow(new OptimisticLockException("Row updated by another transaction"));

        Cart cart = Cart.builder().id(10L).build();
        CartItem item = CartItem.builder().id(101L).cart(cart).product(sampleProduct).quantity(2).build();

        // Must propagate OptimisticLockException out immediately without inner retry loop
        assertThatThrownBy(() -> strategy.reserveInventory(sampleOrder, List.of(item)))
                .isInstanceOf(OptimisticLockException.class);

        verify(inventoryRepository, times(1)).saveAndFlush(any(Inventory.class));
    }
}
