package com.checkout.engine.job;

import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.InventoryReservation;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.entity.ReservationStatus;
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.InventoryReservationRepository;
import com.checkout.engine.repository.OrderRepository;
import com.checkout.engine.service.OrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpiryJob {

    private final InventoryReservationRepository inventoryReservationRepository;
    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderStateMachine orderStateMachine;

    @Scheduled(fixedDelayString = "${checkout.reservation.expiry.fixed-delay-ms:30000}")
    @Transactional
    public void sweepExpiredReservations() {
        List<InventoryReservation> expiredReservations = inventoryReservationRepository.findExpiredActiveReservationsWithSkipLocked();
        if (expiredReservations.isEmpty()) {
            return;
        }

        log.info("Found {} expired ACTIVE inventory reservations to sweep", expiredReservations.size());

        // Group expired ACTIVE reservations by orderId
        Map<Long, List<InventoryReservation>> reservationsByOrder = expiredReservations.stream()
                .collect(Collectors.groupingBy(r -> r.getOrder().getId()));

        for (Map.Entry<Long, List<InventoryReservation>> entry : reservationsByOrder.entrySet()) {
            Long orderId = entry.getKey();
            List<InventoryReservation> reservations = entry.getValue();

            processExpiredOrderReservations(orderId, reservations);
        }
    }

    public void processExpiredOrderReservations(Long orderId, List<InventoryReservation> reservations) {
        // 1. Acquire pessimistic lock on Order BEFORE locking inventory rows (matches PaymentWebhookService)
        Order order = orderRepository.findByIdWithPessimisticLock(orderId)
                .orElse(null);

        if (order == null) {
            log.warn("Order {} not found while sweeping expired reservations", orderId);
            return;
        }

        // If the order has already been CONFIRMED, do not expire its reservations
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            log.info("Order {} is already CONFIRMED; skipping expiry sweep", order.getOrderNumber());
            return;
        }

        // 2. Transition parent order to EXPIRED
        if (order.getStatus() != OrderStatus.EXPIRED) {
            if (orderStateMachine.canTransition(order.getStatus(), OrderStatus.EXPIRED)) {
                order.setStatus(OrderStatus.EXPIRED);
                orderRepository.save(order);
                log.info("Transitioned order {} from PENDING_PAYMENT to EXPIRED due to reservation timeout", order.getOrderNumber());
            } else {
                log.warn("Cannot transition order {} from {} to EXPIRED", order.getOrderNumber(), order.getStatus());
            }
        }

        // 3. Sort reservations by productId in ascending order to prevent deadlocks
        List<InventoryReservation> sortedReservations = reservations.stream()
                .sorted(Comparator.comparing(r -> r.getProduct().getId()))
                .toList();

        // 4. Release reserved_qty back to available_qty and mark reservations EXPIRED
        for (InventoryReservation res : sortedReservations) {
            if (res.getStatus() == ReservationStatus.ACTIVE) {
                res.setStatus(ReservationStatus.EXPIRED);
                inventoryReservationRepository.save(res);

                Long productId = res.getProduct().getId();
                Inventory inventory = inventoryRepository.findByProductIdWithPessimisticLock(productId)
                        .orElseThrow(() -> new ResourceNotFoundException("Inventory", "productId", productId));

                int newAvailable = inventory.getAvailableQty() + res.getQuantity();
                int newReserved = Math.max(0, inventory.getReservedQty() - res.getQuantity());
                inventory.setAvailableQty(newAvailable);
                inventory.setReservedQty(newReserved);
                inventoryRepository.save(inventory);

                log.info("Released expired reservation {} for order {}: product={}, restored availableQty to {}, reservedQty to {}",
                        res.getId(), order.getOrderNumber(), productId, newAvailable, newReserved);
            }
        }
    }
}
