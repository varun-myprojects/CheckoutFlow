package com.checkout.engine.service;

import com.checkout.engine.dto.CartItemResponse;
import com.checkout.engine.dto.CartResponse;
import com.checkout.engine.dto.SavedItemResponse;
import com.checkout.engine.entity.Cart;
import com.checkout.engine.entity.CartItem;
import com.checkout.engine.entity.CartStatus;
import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.Product;
import com.checkout.engine.entity.SavedItem;
import com.checkout.engine.entity.User;
import com.checkout.engine.exception.InsufficientStockException;
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.CartItemRepository;
import com.checkout.engine.repository.CartRepository;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.ProductRepository;
import com.checkout.engine.repository.SavedItemRepository;
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
public class SavedItemService {

    private final SavedItemRepository savedItemRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final CacheService cacheService;

    @Value("${app.cache.saved-ttl-seconds:600}")
    private long savedTtlSeconds;

    @Transactional
    public SavedItemResponse saveForLater(Long userId, Long productId) {
        User user = findUserById(userId);
        Cart cart = getActiveCart(user.getId());

        CartItem cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new ResourceNotFoundException("CartItem", "productId", productId));

        Product product = cartItem.getProduct();
        BigDecimal currentPrice = product.getPrice();

        // Clean merge/update if already in saved_items
        SavedItem savedItem = savedItemRepository.findByUserIdAndProductId(userId, productId)
                .map(existing -> {
                    existing.setPriceAtSave(currentPrice);
                    return savedItemRepository.save(existing);
                })
                .orElseGet(() -> savedItemRepository.save(
                        SavedItem.builder()
                                .user(user)
                                .product(product)
                                .priceAtSave(currentPrice)
                                .build()
                ));

        // Remove from cart_items atomically
        cartItemRepository.delete(cartItem);

        Inventory inventory = inventoryRepository.findByProductId(productId).orElse(null);
        int availableQty = inventory != null ? inventory.getAvailableQty() : 0;

        SavedItemResponse response = mapToSavedItemResponse(savedItem, product, availableQty);
        cacheService.evict("cart:" + userId);
        cacheService.evict("saved:" + userId);
        return response;
    }

    @Transactional
    public CartResponse moveToCart(Long userId, Long productId) {
        User user = findUserById(userId);
        SavedItem savedItem = savedItemRepository.findByUserIdAndProductId(userId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("SavedItem", "productId", productId));

        Product product = savedItem.getProduct();
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", "productId", productId));

        Cart cart = getOrCreateActiveCart(user);
        CartItem existingCartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElse(null);

        int targetQuantity = (existingCartItem != null ? existingCartItem.getQuantity() : 0) + 1;

        if (targetQuantity > inventory.getAvailableQty()) {
            throw new InsufficientStockException(product.getSku(), targetQuantity, inventory.getAvailableQty());
        }

        if (existingCartItem != null) {
            existingCartItem.setQuantity(targetQuantity);
            cartItemRepository.save(existingCartItem);
        } else {
            CartItem newCartItem = CartItem.builder()
                    .cart(cart)
                    .product(product)
                    .quantity(1)
                    .build();
            cartItemRepository.save(newCartItem);
        }

        // Delete from saved_items atomically
        savedItemRepository.delete(savedItem);

        CartResponse response = buildCartResponse(cart);
        cacheService.evict("cart:" + userId);
        cacheService.evict("saved:" + userId);
        return response;
    }

    @Transactional(readOnly = true)
    public List<SavedItemResponse> getSavedItems(Long userId) {
        String cacheKey = "saved:" + userId;
        return cacheService.getList(cacheKey, SavedItemResponse.class)
                .orElseGet(() -> {
                    findUserById(userId);
                    List<SavedItem> savedItems = savedItemRepository.findByUserId(userId);
                    List<Long> productIds = savedItems.stream()
                            .map(item -> item.getProduct().getId())
                            .distinct()
                            .toList();

                    Map<Long, Inventory> inventoryMap = productIds.isEmpty() ? Collections.emptyMap() :
                            inventoryRepository.findByProductIdIn(productIds).stream()
                                    .collect(Collectors.toMap(inv -> inv.getProduct().getId(), inv -> inv, (a, b) -> a));

                    List<SavedItemResponse> responses = new ArrayList<>();
                    for (SavedItem item : savedItems) {
                        Product product = item.getProduct();
                        Inventory inventory = inventoryMap.get(product.getId());
                        int availableStock = inventory != null ? inventory.getAvailableQty() : 0;
                        responses.add(mapToSavedItemResponse(item, product, availableStock));
                    }

                    cacheService.put(cacheKey, responses, Duration.ofSeconds(savedTtlSeconds));
                    return responses;
                });
    }

    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }

    private Cart getActiveCart(Long userId) {
        return cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Active cart", "userId", userId));
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

    private SavedItemResponse mapToSavedItemResponse(SavedItem item, Product product, int availableStock) {
        BigDecimal currentPrice = product.getPrice();
        BigDecimal priceAtSave = item.getPriceAtSave();
        BigDecimal priceDiff = currentPrice.subtract(priceAtSave);

        String indicator;
        int cmp = priceDiff.compareTo(BigDecimal.ZERO);
        if (cmp < 0) {
            indicator = "PRICE_DROPPED";
        } else if (cmp > 0) {
            indicator = "PRICE_INCREASED";
        } else {
            indicator = "NO_CHANGE";
        }

        return SavedItemResponse.builder()
                .id(item.getId())
                .productId(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .currentPrice(currentPrice)
                .priceAtSave(priceAtSave)
                .priceDifference(priceDiff)
                .priceDiffIndicator(indicator)
                .inStock(availableStock > 0)
                .availableStock(availableStock)
                .savedAt(item.getCreatedAt())
                .build();
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
