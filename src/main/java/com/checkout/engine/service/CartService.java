package com.checkout.engine.service;

import com.checkout.engine.dto.AddToCartRequest;
import com.checkout.engine.dto.CartItemResponse;
import com.checkout.engine.dto.CartResponse;
import com.checkout.engine.dto.UpdateCartItemRequest;
import com.checkout.engine.entity.Cart;
import com.checkout.engine.entity.CartItem;
import com.checkout.engine.entity.CartStatus;
import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.Product;
import com.checkout.engine.entity.User;
import com.checkout.engine.exception.InsufficientStockException;
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.CartItemRepository;
import com.checkout.engine.repository.CartRepository;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.ProductRepository;
import com.checkout.engine.repository.UserRepository;
import com.checkout.engine.service.cache.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final CacheService cacheService;

    @Value("${app.cache.cart-ttl-seconds:300}")
    private long cartTtlSeconds;

    @Transactional
    public CartResponse getCart(Long userId) {
        String cacheKey = "cart:" + userId;
        return cacheService.get(cacheKey, CartResponse.class)
                .orElseGet(() -> {
                    User user = findUserById(userId);
                    Cart cart = getOrCreateActiveCart(user);
                    CartResponse response = buildCartResponse(cart);
                    cacheService.put(cacheKey, response, Duration.ofSeconds(cartTtlSeconds));
                    return response;
                });
    }

    @Transactional
    public CartResponse addItemToCart(Long userId, AddToCartRequest request) {
        User user = findUserById(userId);
        Cart cart = getOrCreateActiveCart(user);

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", request.getProductId()));

        Inventory inventory = inventoryRepository.findByProductId(product.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", "productId", product.getId()));

        CartItem existingItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), product.getId())
                .orElse(null);

        int targetQuantity = (existingItem != null ? existingItem.getQuantity() : 0) + request.getQuantity();

        if (targetQuantity > inventory.getAvailableQty()) {
            throw new InsufficientStockException(product.getSku(), targetQuantity, inventory.getAvailableQty());
        }

        if (existingItem != null) {
            existingItem.setQuantity(targetQuantity);
            cartItemRepository.save(existingItem);
        } else {
            CartItem newItem = CartItem.builder()
                    .cart(cart)
                    .product(product)
                    .quantity(targetQuantity)
                    .build();
            cartItemRepository.save(newItem);
        }

        CartResponse response = buildCartResponse(cart);
        cacheService.evict("cart:" + userId);
        return response;
    }

    @Transactional
    public CartResponse updateItemQuantity(Long userId, Long productId, UpdateCartItemRequest request) {
        User user = findUserById(userId);
        Cart cart = getActiveCart(user.getId());

        CartItem cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new ResourceNotFoundException("CartItem", "productId", productId));

        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", "productId", productId));

        if (request.getQuantity() > inventory.getAvailableQty()) {
            throw new InsufficientStockException(cartItem.getProduct().getSku(), request.getQuantity(), inventory.getAvailableQty());
        }

        cartItem.setQuantity(request.getQuantity());
        cartItemRepository.save(cartItem);

        CartResponse response = buildCartResponse(cart);
        cacheService.evict("cart:" + userId);
        return response;
    }

    @Transactional
    public CartResponse removeItemFromCart(Long userId, Long productId) {
        User user = findUserById(userId);
        Cart cart = getActiveCart(user.getId());

        CartItem cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new ResourceNotFoundException("CartItem", "productId", productId));

        cartItemRepository.delete(cartItem);

        CartResponse response = buildCartResponse(cart);
        cacheService.evict("cart:" + userId);
        return response;
    }

    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }

    private Cart getOrCreateActiveCart(User user) {
        return cartRepository.findByUserIdAndStatus(user.getId(), CartStatus.ACTIVE)
                .orElseGet(() -> cartRepository.save(
                        Cart.builder()
                                .user(user)
                                .status(CartStatus.ACTIVE)
                                .build()
                ));
    }

    private Cart getActiveCart(Long userId) {
        return cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Active cart", "userId", userId));
    }

    private CartResponse buildCartResponse(Cart cart) {
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        List<CartItemResponse> itemResponses = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        int totalItems = 0;

        List<Long> productIds = items.stream()
                .map(item -> item.getProduct().getId())
                .distinct()
                .toList();

        Map<Long, Inventory> inventoryMap = productIds.isEmpty() ? Collections.emptyMap() :
                inventoryRepository.findByProductIdIn(productIds).stream()
                        .collect(Collectors.toMap(inv -> inv.getProduct().getId(), inv -> inv, (a, b) -> a));

        for (CartItem item : items) {
            Product product = item.getProduct();
            BigDecimal unitPrice = product.getPrice();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(lineTotal);
            totalItems += item.getQuantity();

            Inventory inventory = inventoryMap.get(product.getId());
            Integer availableStock = inventory != null ? inventory.getAvailableQty() : 0;

            itemResponses.add(CartItemResponse.builder()
                    .id(item.getId())
                    .productId(product.getId())
                    .sku(product.getSku())
                    .name(product.getName())
                    .unitPrice(unitPrice)
                    .quantity(item.getQuantity())
                    .lineTotal(lineTotal)
                    .availableStock(availableStock)
                    .build());
        }

        return CartResponse.builder()
                .cartId(cart.getId())
                .userId(cart.getUser().getId())
                .status(cart.getStatus())
                .items(itemResponses)
                .subtotal(subtotal)
                .totalItems(totalItems)
                .updatedAt(cart.getUpdatedAt())
                .build();
    }
}
