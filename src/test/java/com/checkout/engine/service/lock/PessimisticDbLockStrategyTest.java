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
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.InventoryReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PessimisticDbLockStrategyTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private InventoryReservationRepository inventoryReservationRepository;

    @InjectMocks
    private PessimisticDbLockStrategy strategy;

    private Order sampleOrder;
    private Product productA;
    private Product productB;
    private Inventory inventoryA;
    private Inventory inventoryB;

    @BeforeEach
    void setUp() {
        User user = User.builder().id(1L).email("user@example.com").name("User").build();
        sampleOrder = Order.builder().id(100L).orderNumber("ORD-100").user(user).build();

        productA = Product.builder().id(2L).sku("PROD-A").price(BigDecimal.TEN).build();
        productB = Product.builder().id(5L).sku("PROD-B").price(BigDecimal.ONE).build();

        inventoryA = Inventory.builder().id(1L).product(productA).availableQty(10).reservedQty(0).build();
        inventoryB = Inventory.builder().id(2L).product(productB).availableQty(20).reservedQty(0).build();
    }

    @Test
    void reserveInventory_sortsProductIdsAscendingToPreventDeadlocks() {
        // Given cart items in reverse product order: ID 5 then ID 2
        Cart cart = Cart.builder().id(10L).build();
        CartItem itemB = CartItem.builder().id(101L).cart(cart).product(productB).quantity(3).build(); // product id 5
        CartItem itemA = CartItem.builder().id(102L).cart(cart).product(productA).quantity(2).build(); // product id 2

        when(inventoryRepository.findByProductIdWithPessimisticLock(2L)).thenReturn(Optional.of(inventoryA));
        when(inventoryRepository.findByProductIdWithPessimisticLock(5L)).thenReturn(Optional.of(inventoryB));
        when(inventoryReservationRepository.save(any(InventoryReservation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        List<InventoryReservation> reservations = strategy.reserveInventory(sampleOrder, List.of(itemB, itemA));

        // Then: verify lock was acquired in strict ascending product ID order: 2L then 5L
        InOrder inOrder = inOrder(inventoryRepository);
        inOrder.verify(inventoryRepository).findByProductIdWithPessimisticLock(2L);
        inOrder.verify(inventoryRepository).findByProductIdWithPessimisticLock(5L);

        // Verify stock updates
        assertThat(inventoryA.getAvailableQty()).isEqualTo(8);
        assertThat(inventoryA.getReservedQty()).isEqualTo(2);
        assertThat(inventoryB.getAvailableQty()).isEqualTo(17);
        assertThat(inventoryB.getReservedQty()).isEqualTo(3);

        // Verify reservations
        assertThat(reservations).hasSize(2);
        assertThat(reservations.get(0).getProduct().getId()).isEqualTo(2L);
        assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservations.get(0).getExpiresAt()).isAfter(Instant.now());
        assertThat(reservations.get(1).getProduct().getId()).isEqualTo(5L);
    }

    @Test
    void reserveInventory_insufficientStock_throwsException() {
        Cart cart = Cart.builder().id(10L).build();
        CartItem itemA = CartItem.builder().id(101L).cart(cart).product(productA).quantity(15).build(); // wants 15, available 10

        when(inventoryRepository.findByProductIdWithPessimisticLock(2L)).thenReturn(Optional.of(inventoryA));

        assertThatThrownBy(() -> strategy.reserveInventory(sampleOrder, List.of(itemA)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Requested: 15, Available: 10");

        verify(inventoryReservationRepository, never()).save(any());
        assertThat(inventoryA.getAvailableQty()).isEqualTo(10);
    }

    @Test
    void reserveInventory_missingInventory_throwsNotFound() {
        Cart cart = Cart.builder().id(10L).build();
        CartItem itemA = CartItem.builder().id(101L).cart(cart).product(productA).quantity(1).build();

        when(inventoryRepository.findByProductIdWithPessimisticLock(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> strategy.reserveInventory(sampleOrder, List.of(itemA)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Inventory not found with productId");
    }
}
