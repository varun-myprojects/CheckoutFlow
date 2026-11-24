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
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component("optimisticLockStrategy")
@RequiredArgsConstructor
public class OptimisticLockStrategy implements InventoryLockStrategy {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 50L;

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository inventoryReservationRepository;

    @Override
    public List<InventoryReservation> reserveInventory(Order order, List<CartItem> cartItems) {
        // Sort cart items by product ID in ascending order for consistent processing
        List<CartItem> sortedItems = cartItems.stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .toList();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return executeReservationAttempt(order, sortedItems);
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
                log.warn("Optimistic locking conflict detected on attempt {}/{} for order {}: {}",
                        attempt, MAX_ATTEMPTS, order.getOrderNumber(), e.getMessage());

                if (attempt >= MAX_ATTEMPTS) {
                    log.error("Exhausted all {} retry attempts for order {} due to optimistic lock contention",
                            MAX_ATTEMPTS, order.getOrderNumber());
                    throw e;
                }

                try {
                    Thread.sleep(RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during optimistic lock retry", ie);
                }
            }
        }

        throw new IllegalStateException("Failed to complete inventory reservation within retry limit");
    }

    protected List<InventoryReservation> executeReservationAttempt(Order order, List<CartItem> sortedItems) {
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

            // Flushes immediately so JPA @Version check triggers OptimisticLockException on version mismatch
            inventoryRepository.saveAndFlush(inventory);

            InventoryReservation reservation = InventoryReservation.builder()
                    .order(order)
                    .product(product)
                    .quantity(requestedQty)
                    .status(ReservationStatus.ACTIVE)
                    .expiresAt(expiresAt)
                    .build();

            reservations.add(inventoryReservationRepository.save(reservation));
            log.info("Reserved {} units of product {} for order {} (optimistic version: {})",
                    requestedQty, product.getSku(), order.getOrderNumber(), inventory.getVersion());
        }

        return reservations;
    }
}
