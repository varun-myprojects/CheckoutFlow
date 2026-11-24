package com.checkout.engine.job;

import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.InventoryReservation;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.entity.Product;
import com.checkout.engine.entity.ReservationStatus;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.InventoryReservationRepository;
import com.checkout.engine.repository.OrderRepository;
import com.checkout.engine.service.OrderStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationExpiryJobTest {

    @Mock
    private InventoryReservationRepository inventoryReservationRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Spy
    private OrderStateMachine orderStateMachine = new OrderStateMachine();

    @InjectMocks
    private ReservationExpiryJob expiryJob;

    private Order order1;
    private Product productA;
    private Product productB;
    private Inventory inventoryA;
    private Inventory inventoryB;
    private InventoryReservation resA;
    private InventoryReservation resB;

    @BeforeEach
    void setUp() {
        order1 = Order.builder()
                .id(10L)
                .orderNumber("ORD-EXP-001")
                .status(OrderStatus.PENDING_PAYMENT)
                .build();

        productA = Product.builder().id(100L).sku("PROD-A").price(BigDecimal.valueOf(50)).build();
        productB = Product.builder().id(200L).sku("PROD-B").price(BigDecimal.valueOf(100)).build();

        inventoryA = Inventory.builder().id(1L).product(productA).availableQty(10).reservedQty(2).build();
        inventoryB = Inventory.builder().id(2L).product(productB).availableQty(20).reservedQty(3).build();

        // Put in reverse product ID order to verify ascending sort
        resB = InventoryReservation.builder()
                .id(502L)
                .order(order1)
                .product(productB)
                .quantity(3)
                .status(ReservationStatus.ACTIVE)
                .expiresAt(Instant.now().minusSeconds(10))
                .build();

        resA = InventoryReservation.builder()
                .id(501L)
                .order(order1)
                .product(productA)
                .quantity(2)
                .status(ReservationStatus.ACTIVE)
                .expiresAt(Instant.now().minusSeconds(10))
                .build();
    }

    @Test
    void sweepExpiredReservations_empty_doesNothing() {
        when(inventoryReservationRepository.findExpiredActiveReservationsWithSkipLocked())
                .thenReturn(Collections.emptyList());

        expiryJob.sweepExpiredReservations();

        verify(orderRepository, never()).findByIdWithPessimisticLock(any());
        verify(inventoryRepository, never()).findByProductIdWithPessimisticLock(any());
    }

    @Test
    void sweepExpiredReservations_locksOrderFirst_thenLocksInventoryInAscendingOrder_andReleasesStock() {
        when(inventoryReservationRepository.findExpiredActiveReservationsWithSkipLocked())
                .thenReturn(List.of(resB, resA)); // unsorted
        when(orderRepository.findByIdWithPessimisticLock(10L)).thenReturn(Optional.of(order1));
        when(inventoryRepository.findByProductIdWithPessimisticLock(100L)).thenReturn(Optional.of(inventoryA));
        when(inventoryRepository.findByProductIdWithPessimisticLock(200L)).thenReturn(Optional.of(inventoryB));

        expiryJob.sweepExpiredReservations();

        // Verify order lock happens BEFORE inventory locks, and product 100 is locked before product 200
        InOrder inOrder = inOrder(orderRepository, inventoryRepository);
        inOrder.verify(orderRepository).findByIdWithPessimisticLock(10L);
        inOrder.verify(inventoryRepository).findByProductIdWithPessimisticLock(100L);
        inOrder.verify(inventoryRepository).findByProductIdWithPessimisticLock(200L);

        // Verify order transitioned to EXPIRED
        assertThat(order1.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(orderRepository).save(order1);

        // Verify reservations marked EXPIRED
        assertThat(resA.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(resB.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        verify(inventoryReservationRepository).save(resA);
        verify(inventoryReservationRepository).save(resB);

        // Verify stock released back to availableQty and reservedQty decremented
        assertThat(inventoryA.getAvailableQty()).isEqualTo(12); // 10 + 2
        assertThat(inventoryA.getReservedQty()).isEqualTo(0);   // 2 - 2
        assertThat(inventoryB.getAvailableQty()).isEqualTo(23); // 20 + 3
        assertThat(inventoryB.getReservedQty()).isEqualTo(0);   // 3 - 3
        verify(inventoryRepository).save(inventoryA);
        verify(inventoryRepository).save(inventoryB);
    }

    @Test
    void sweepExpiredReservations_alreadyConfirmedOrder_skipsExpiry() {
        order1.setStatus(OrderStatus.CONFIRMED);
        when(inventoryReservationRepository.findExpiredActiveReservationsWithSkipLocked())
                .thenReturn(List.of(resA));
        when(orderRepository.findByIdWithPessimisticLock(10L)).thenReturn(Optional.of(order1));

        expiryJob.sweepExpiredReservations();

        verify(orderRepository, never()).save(any());
        verify(inventoryRepository, never()).save(any());
        verify(inventoryReservationRepository, never()).save(any());
    }
}
