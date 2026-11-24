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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component("pessimisticDbLockStrategy")
@RequiredArgsConstructor
public class PessimisticDbLockStrategy implements InventoryLockStrategy {

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository inventoryReservationRepository;

    @Override
    @Transactional
    public List<InventoryReservation> reserveInventory(Order order, List<CartItem> cartItems) {
        // Sort cart items by product ID in ascending order to prevent deadlocks across concurrent multi-item checkouts
        List<CartItem> sortedItems = cartItems.stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .toList();

        List<InventoryReservation> reservations = new ArrayList<>();
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);

        for (CartItem item : sortedItems) {
            Product product = item.getProduct();
            Long productId = product.getId();
            int requestedQty = item.getQuantity();

            log.debug("Acquiring pessimistic row lock for product ID: {}", productId);
            Inventory inventory = inventoryRepository.findByProductIdWithPessimisticLock(productId)
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
            log.info("Reserved {} units of product {} for order {}", requestedQty, product.getSku(), order.getOrderNumber());
        }

        return reservations;
    }
}
