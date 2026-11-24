package com.checkout.engine.service;

import com.checkout.engine.dto.CheckoutResponse;
import com.checkout.engine.dto.OrderItemResponse;
import com.checkout.engine.entity.Cart;
import com.checkout.engine.entity.CartItem;
import com.checkout.engine.entity.CartStatus;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.OrderItem;
import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.entity.Product;
import com.checkout.engine.entity.User;
import com.checkout.engine.exception.DuplicateRequestException;
import com.checkout.engine.exception.InvalidStateTransitionException;
import com.checkout.engine.exception.LockAcquisitionException;
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.CartItemRepository;
import com.checkout.engine.repository.CartRepository;
import com.checkout.engine.repository.OrderItemRepository;
import com.checkout.engine.repository.OrderRepository;
import com.checkout.engine.repository.UserRepository;
import com.checkout.engine.service.lock.InventoryLockStrategy;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private static final int MAX_CHECKOUT_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 50L;

    private final UserRepository userRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryLockStrategy inventoryLockStrategy;
    private final OrderStateMachine orderStateMachine;
    private final CheckoutTransactionRunner checkoutTransactionRunner;

    public CheckoutResponse checkout(Long userId, String idempotencyKey) {
        // 0. Validate and normalize Idempotency-Key
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
        String normalizedIdempotencyKey = idempotencyKey.trim();

        // 1. Idempotency Check upfront (replays bypass cart lookup and locking)
        var existingOrderOpt = orderRepository.findByIdempotencyKey(normalizedIdempotencyKey);
        if (existingOrderOpt.isPresent()) {
            Order existingOrder = existingOrderOpt.get();
            if (!existingOrder.getUser().getId().equals(userId)) {
                throw new DuplicateRequestException("Idempotency key already used by another user.");
            }
            log.info("Replaying existing order {} for idempotency key {}", existingOrder.getOrderNumber(), normalizedIdempotencyKey);
            return mapToCheckoutResponse(existingOrder);
        }

        // 2. Fetch sorted productIds of user's active cart (read-only pre-lookup)
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Cart cart = cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                .orElseThrow(() -> new InvalidStateTransitionException("Cannot checkout: Active cart not found for user " + userId));

        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());
        if (cartItems.isEmpty()) {
            throw new InvalidStateTransitionException("Cannot checkout: Active cart is empty");
        }

        List<Long> lockedProductIds = cartItems.stream()
                .map(item -> item.getProduct().getId())
                .distinct()
                .sorted()
                .toList();

        // 3. Retry loop for optimistic lock conflicts, wrapping DB transaction inside distributed lock
        for (int attempt = 1; attempt <= MAX_CHECKOUT_ATTEMPTS; attempt++) {
            try {
                return inventoryLockStrategy.executeWithDistributedLock(lockedProductIds, () ->
                        checkoutTransactionRunner.runInTransaction(() ->
                                doCheckout(userId, normalizedIdempotencyKey, lockedProductIds)
                        )
                );
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
                log.warn("Optimistic lock conflict on checkout attempt {}/{} for user {}: {}",
                        attempt, MAX_CHECKOUT_ATTEMPTS, userId, e.getMessage());

                if (attempt >= MAX_CHECKOUT_ATTEMPTS) {
                    log.error("Exhausted all {} retry attempts for user {} due to optimistic lock contention",
                            MAX_CHECKOUT_ATTEMPTS, userId);
                    throw e;
                }

                try {
                    Thread.sleep(RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during optimistic checkout retry", ie);
                }
            }
        }

        throw new IllegalStateException("Failed to complete checkout within retry limit");
    }

    protected CheckoutResponse doCheckout(Long userId, String normalizedIdempotencyKey, List<Long> lockedProductIds) {
        // User & Cart Validation
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Cart cart = cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                .orElseThrow(() -> new InvalidStateTransitionException("Cannot checkout: Active cart not found for user " + userId));

        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());
        if (cartItems.isEmpty()) {
            throw new InvalidStateTransitionException("Cannot checkout: Active cart is empty");
        }

        List<Long> currentProductIds = cartItems.stream()
                .map(item -> item.getProduct().getId())
                .distinct()
                .sorted()
                .toList();

        if (!currentProductIds.equals(lockedProductIds)) {
            throw new LockAcquisitionException("Cart items changed concurrently during checkout lock acquisition");
        }

        // Calculate subtotal & snapshot prices
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartItem item : cartItems) {
            BigDecimal itemTotal = item.getProduct().getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            totalAmount = totalAmount.add(itemTotal);
        }

        // Create Order initially in CREATED status with normalized (trimmed) idempotency key
        String orderNumber = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        Order order = Order.builder()
                .orderNumber(orderNumber)
                .user(user)
                .status(OrderStatus.CREATED)
                .totalAmount(totalAmount)
                .idempotencyKey(normalizedIdempotencyKey)
                .build();

        // Validate transition CREATED -> PENDING_PAYMENT via strict OrderStateMachine
        orderStateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.PENDING_PAYMENT);
        order.setStatus(OrderStatus.PENDING_PAYMENT);

        Order savedOrder = orderRepository.save(order);

        // Snapshot Order Items
        List<OrderItem> savedOrderItems = new ArrayList<>();
        for (CartItem cartItem : cartItems) {
            Product product = cartItem.getProduct();
            OrderItem orderItem = OrderItem.builder()
                    .order(savedOrder)
                    .product(product)
                    .quantity(cartItem.getQuantity())
                    .price(product.getPrice()) // Snapshot current price
                    .build();
            savedOrderItems.add(orderItemRepository.save(orderItem));
        }

        // Real Inventory Reservation via selected locking strategy
        inventoryLockStrategy.reserveInventory(savedOrder, cartItems);

        // Transition cart to ORDERED (saved_items are completely untouched)
        cart.setStatus(CartStatus.ORDERED);
        cartRepository.save(cart);

        log.info("Checkout successful. Order {} created for user {} with status {}",
                savedOrder.getOrderNumber(), userId, savedOrder.getStatus());

        return mapToCheckoutResponse(savedOrder, savedOrderItems);
    }

    private CheckoutResponse mapToCheckoutResponse(Order order) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        return mapToCheckoutResponse(order, items);
    }

    private CheckoutResponse mapToCheckoutResponse(Order order, List<OrderItem> items) {
        List<OrderItemResponse> itemResponses = new ArrayList<>();
        for (OrderItem item : items) {
            Product product = item.getProduct();
            BigDecimal lineTotal = item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            itemResponses.add(OrderItemResponse.builder()
                    .id(item.getId())
                    .productId(product.getId())
                    .sku(product.getSku())
                    .name(product.getName())
                    .quantity(item.getQuantity())
                    .price(item.getPrice())
                    .lineTotal(lineTotal)
                    .build());
        }

        return CheckoutResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .userId(order.getUser().getId())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .idempotencyKey(order.getIdempotencyKey())
                .items(itemResponses)
                .createdAt(order.getCreatedAt())
                .build();
    }
}
