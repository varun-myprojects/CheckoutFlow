package com.checkout.engine.service;

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
import com.checkout.engine.repository.CartItemRepository;
import com.checkout.engine.repository.CartRepository;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.ProductRepository;
import com.checkout.engine.repository.SavedItemRepository;
import com.checkout.engine.repository.UserRepository;
import com.checkout.engine.service.cache.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedItemServiceTest {

    @Mock
    private SavedItemRepository savedItemRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private SavedItemService savedItemService;

    private User sampleUser;
    private Product sampleProduct;
    private Inventory sampleInventory;
    private Cart sampleCart;
    private CartItem sampleCartItem;
    private SavedItem sampleSavedItem;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .name("Test User")
                .build();

        sampleProduct = Product.builder()
                .id(10L)
                .sku("SKU-10")
                .name("Test Product")
                .price(BigDecimal.valueOf(100.00))
                .status("ACTIVE")
                .build();

        sampleInventory = Inventory.builder()
                .id(100L)
                .product(sampleProduct)
                .availableQty(10)
                .reservedQty(0)
                .version(0L)
                .build();

        sampleCart = Cart.builder()
                .id(50L)
                .user(sampleUser)
                .status(CartStatus.ACTIVE)
                .build();

        sampleCartItem = CartItem.builder()
                .id(501L)
                .cart(sampleCart)
                .product(sampleProduct)
                .quantity(2)
                .build();

        sampleSavedItem = SavedItem.builder()
                .id(601L)
                .user(sampleUser)
                .product(sampleProduct)
                .priceAtSave(BigDecimal.valueOf(120.00))
                .build();

        lenient().when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(sampleInventory));
    }

    @Test
    void saveForLater_newItem_success() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.of(sampleCartItem));
        when(savedItemRepository.findByUserIdAndProductId(1L, 10L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductId(10L)).thenReturn(Optional.of(sampleInventory));

        SavedItem createdSavedItem = SavedItem.builder()
                .id(1L)
                .user(sampleUser)
                .product(sampleProduct)
                .priceAtSave(BigDecimal.valueOf(100.00))
                .build();

        when(savedItemRepository.save(any(SavedItem.class))).thenReturn(createdSavedItem);

        SavedItemResponse response = savedItemService.saveForLater(1L, 10L);

        assertThat(response).isNotNull();
        assertThat(response.getProductId()).isEqualTo(10L);
        assertThat(response.getPriceAtSave()).isEqualByComparingTo("100.00");
        assertThat(response.getCurrentPrice()).isEqualByComparingTo("100.00");
        assertThat(response.getPriceDiffIndicator()).isEqualTo("NO_CHANGE");
        assertThat(response.isInStock()).isTrue();

        verify(savedItemRepository).save(any(SavedItem.class));
        verify(cartItemRepository).delete(sampleCartItem);
    }

    @Test
    void saveForLater_mergeExistingSavedItem_updatesPriceAtSaveWithoutConstraintViolation() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.of(sampleCartItem));
        when(savedItemRepository.findByUserIdAndProductId(1L, 10L)).thenReturn(Optional.of(sampleSavedItem));
        when(savedItemRepository.save(sampleSavedItem)).thenReturn(sampleSavedItem);
        when(inventoryRepository.findByProductId(10L)).thenReturn(Optional.of(sampleInventory));

        SavedItemResponse response = savedItemService.saveForLater(1L, 10L);

        assertThat(response).isNotNull();
        assertThat(sampleSavedItem.getPriceAtSave()).isEqualByComparingTo("100.00");
        verify(savedItemRepository).save(sampleSavedItem);
        verify(cartItemRepository).delete(sampleCartItem);
    }

    @Test
    void saveForLater_midTransferFailure_throwsExceptionAndRequiresTransactionalRollback() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.of(sampleCartItem));
        when(savedItemRepository.findByUserIdAndProductId(1L, 10L)).thenReturn(Optional.empty());

        // Simulate mid-transfer crash when persisting saved item
        when(savedItemRepository.save(any(SavedItem.class)))
                .thenThrow(new RuntimeException("Database connection severed during save-for-later"));

        assertThatThrownBy(() -> savedItemService.saveForLater(1L, 10L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database connection severed during save-for-later");

        // Cart item deletion must not have been executed if saving saved_item failed
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void moveToCart_success() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(savedItemRepository.findByUserIdAndProductId(1L, 10L)).thenReturn(Optional.of(sampleSavedItem));
        when(inventoryRepository.findByProductId(10L)).thenReturn(Optional.of(sampleInventory)); // 10 available
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.empty());

        CartItem newCartItem = CartItem.builder()
                .id(1L)
                .cart(sampleCart)
                .product(sampleProduct)
                .quantity(1)
                .build();
        when(cartItemRepository.findByCartId(50L)).thenReturn(List.of(newCartItem));

        CartResponse response = savedItemService.moveToCart(1L, 10L);

        assertThat(response).isNotNull();
        verify(cartItemRepository).save(any(CartItem.class));
        verify(savedItemRepository).delete(sampleSavedItem);
    }

    @Test
    void moveToCart_insufficientStock_rejectsAndPreservesSavedItem() {
        sampleInventory.setAvailableQty(0); // Out of stock

        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(savedItemRepository.findByUserIdAndProductId(1L, 10L)).thenReturn(Optional.of(sampleSavedItem));
        when(inventoryRepository.findByProductId(10L)).thenReturn(Optional.of(sampleInventory));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> savedItemService.moveToCart(1L, 10L))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Requested: 1, Available: 0");

        verify(cartItemRepository, never()).save(any());
        verify(savedItemRepository, never()).delete(any());
    }

    @Test
    void moveToCart_midTransferFailure_throwsExceptionAndEnsuresRollback() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(savedItemRepository.findByUserIdAndProductId(1L, 10L)).thenReturn(Optional.of(sampleSavedItem));
        when(inventoryRepository.findByProductId(10L)).thenReturn(Optional.of(sampleInventory));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.empty());

        // Simulate crash during cart item insert
        when(cartItemRepository.save(any(CartItem.class)))
                .thenThrow(new RuntimeException("Crash while persisting cart item"));

        assertThatThrownBy(() -> savedItemService.moveToCart(1L, 10L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Crash while persisting cart item");

        // Saved item deletion must never occur if cart insertion fails
        verify(savedItemRepository, never()).delete(any());
    }

    @Test
    void getSavedItems_enrichedPriceDifferences() {
        // Price was 120.00 at save, current price is 100.00 -> PRICE_DROPPED by 20.00
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(savedItemRepository.findByUserId(1L)).thenReturn(List.of(sampleSavedItem));

        List<SavedItemResponse> items = savedItemService.getSavedItems(1L);

        assertThat(items).hasSize(1);
        SavedItemResponse item = items.get(0);
        assertThat(item.getCurrentPrice()).isEqualByComparingTo("100.00");
        assertThat(item.getPriceAtSave()).isEqualByComparingTo("120.00");
        assertThat(item.getPriceDifference()).isEqualByComparingTo("-20.00");
        assertThat(item.getPriceDiffIndicator()).isEqualTo("PRICE_DROPPED");
        assertThat(item.isInStock()).isTrue();
    }

    @Test
    void getSavedItems_cacheHit_returnsCachedListWithoutDbQuery() {
        SavedItemResponse cachedItem = SavedItemResponse.builder()
                .id(601L)
                .productId(10L)
                .sku("SKU-10")
                .name("Test Product")
                .currentPrice(BigDecimal.valueOf(100.00))
                .priceAtSave(BigDecimal.valueOf(120.00))
                .build();

        when(cacheService.getList("saved:1", SavedItemResponse.class)).thenReturn(Optional.of(List.of(cachedItem)));

        List<SavedItemResponse> items = savedItemService.getSavedItems(1L);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getSku()).isEqualTo("SKU-10");
        verify(userRepository, never()).findById(any());
        verify(savedItemRepository, never()).findByUserId(any());
    }

    @Test
    void methodsAreAnnotatedWithTransactional() throws NoSuchMethodException {
        Method saveForLater = SavedItemService.class.getMethod("saveForLater", Long.class, Long.class);
        Method moveToCart = SavedItemService.class.getMethod("moveToCart", Long.class, Long.class);

        assertThat(saveForLater.isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(moveToCart.isAnnotationPresent(Transactional.class)).isTrue();
    }
}
