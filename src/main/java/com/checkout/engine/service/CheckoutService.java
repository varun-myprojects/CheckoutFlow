package com.checkout.engine.service;

import com.checkout.engine.dto.CheckoutResponse;
import com.checkout.engine.dto.OrderItemResponse;
import com.checkout.engine.entity.Cart;
import com.checkout.engine.entity.CartItem;
import com.checkout.engine.entity.CartStatus;
import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.OrderItem;
import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.entity.Product;
import com.checkout.engine.entity.User;
import com.checkout.engine.exception.DuplicateRequestException;
import com.checkout.engine.exception.InsufficientStockException;
import com.checkout.engine.exception.InvalidStateTransitionException;
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.CartItemRepository;
import com.checkout.engine.repository.CartRepository;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.OrderItemRepository;
import com.checkout.engine.repository.OrderRepository;
import com.checkout.engine.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final UserRepository userRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderStateMachine orderStateMachine;

    @Transactional
    public CheckoutResponse checkout(Long userId, String idempotencyKey) {
        // 0. Validate Idempotency-Key
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }

        // 1. Idempotency Check
        var existingOrderOpt = orderRepository.findByIdempotencyKey(idempotencyKey.trim());
        if (existingOrderOpt.isPresent()) {
            Order existingOrder = existingOrderOpt.get();
            if (!existingOrder.getUser().getId().equals(userId)) {
                throw new DuplicateRequestException("Idempotency key already used by another user.");
            }
            log.info("Replaying existing order {} for idempotency key {}", existingOrder.getOrderNumber(), idempotencyKey);
            return mapToCheckoutResponse(existingOrder);
        }

        // 2. User & Cart Validation
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Cart cart = cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                .orElseThrow(() -> new InvalidStateTransitionException("Cannot checkout: Active cart not found for user " + userId));

        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());
        if (cartItems.isEmpty()) {
            throw new InvalidStateTransitionException("Cannot checkout: Active cart is empty");
        }

        // 3. Calculate subtotal & snapshot prices
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartItem item : cartItems) {
            BigDecimal itemTotal = item.getProduct().getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            totalAmount = totalAmount.add(itemTotal);
        }

        // 4. Create Order initially in CREATED status
        String orderNumber = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        Order order = Order.builder()
                .orderNumber(orderNumber)
                .user(user)
                .status(OrderStatus.CREATED)
                .totalAmount(totalAmount)
                .idempotencyKey(idempotencyKey)
                .build();

        // 5. Validate transition CREATED -> PENDING_PAYMENT via strict OrderStateMachine
        orderStateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.PENDING_PAYMENT);
        order.setStatus(OrderStatus.PENDING_PAYMENT);

        Order savedOrder = orderRepository.save(order);

        // 6. Snapshot Order Items
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

        // 7. Stub Inventory Deduction cleanly
        stubInventoryDeduction(cartItems);

        // 8. Transition cart to ORDERED (saved_items are completely untouched)
        cart.setStatus(CartStatus.ORDERED);
        cartRepository.save(cart);

        log.info("Checkout successful. Order {} created for user {} with status {}",
                savedOrder.getOrderNumber(), userId, savedOrder.getStatus());

        return mapToCheckoutResponse(savedOrder, savedOrderItems);
    }

    /**
     * Stubbed inventory deduction: validates stock availability.
     * Actual inventory deduction, reservation tokens, and concurrency locking belong to the next commit.
     */
    protected void stubInventoryDeduction(List<CartItem> cartItems) {
        for (CartItem item : cartItems) {
            Product product = item.getProduct();
            Inventory inventory = inventoryRepository.findByProductId(product.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Inventory", "productId", product.getId()));

            if (inventory.getAvailableQty() < item.getQuantity()) {
                throw new InsufficientStockException(product.getSku(), item.getQuantity(), inventory.getAvailableQty());
            }

            log.debug("Inventory stock validated for product {} with required quantity {}", product.getSku(), item.getQuantity());
        }
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
