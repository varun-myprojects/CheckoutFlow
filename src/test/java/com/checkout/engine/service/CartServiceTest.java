package com.checkout.engine.service;

import com.checkout.engine.dto.AddToCartRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

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

    @InjectMocks
    private CartService cartService;

    private User sampleUser;
    private Product sampleProduct;
    private Inventory sampleInventory;
    private Cart sampleCart;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .name("Test User")
                .build();

        sampleProduct = Product.builder()
                .id(10L)
                .sku("SKU-TEST")
                .name("Test Product")
                .price(BigDecimal.valueOf(50.00))
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
    }

    @Test
    void addItemToCart_newItem_success() {
        AddToCartRequest request = AddToCartRequest.builder()
                .productId(10L)
                .quantity(3)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(productRepository.findById(10L)).thenReturn(Optional.of(sampleProduct));
        when(inventoryRepository.findByProductId(10L)).thenReturn(Optional.of(sampleInventory));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.empty());

        CartItem savedItem = CartItem.builder()
                .id(1L)
                .cart(sampleCart)
                .product(sampleProduct)
                .quantity(3)
                .build();

        when(cartItemRepository.save(any(CartItem.class))).thenReturn(savedItem);
        when(cartItemRepository.findByCartId(50L)).thenReturn(List.of(savedItem));

        CartResponse response = cartService.addItemToCart(1L, request);

        assertThat(response).isNotNull();
        assertThat(response.getCartId()).isEqualTo(50L);
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(3);
        assertThat(response.getItems().get(0).getLineTotal()).isEqualByComparingTo("150.00");
        assertThat(response.getSubtotal()).isEqualByComparingTo("150.00");
        assertThat(response.getTotalItems()).isEqualTo(3);

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualTo(3);
    }

    @Test
    void addItemToCart_upsertMerge_success() {
        AddToCartRequest request = AddToCartRequest.builder()
                .productId(10L)
                .quantity(3)
                .build();

        CartItem existingItem = CartItem.builder()
                .id(1L)
                .cart(sampleCart)
                .product(sampleProduct)
                .quantity(4)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(productRepository.findById(10L)).thenReturn(Optional.of(sampleProduct));
        when(inventoryRepository.findByProductId(10L)).thenReturn(Optional.of(sampleInventory)); // available: 10
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.of(existingItem));

        when(cartItemRepository.findByCartId(50L)).thenReturn(List.of(existingItem));

        CartResponse response = cartService.addItemToCart(1L, request);

        assertThat(response).isNotNull();
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(7); // 4 + 3
        assertThat(response.getItems().get(0).getLineTotal()).isEqualByComparingTo("350.00"); // 7 * 50
        assertThat(response.getSubtotal()).isEqualByComparingTo("350.00");
        assertThat(response.getTotalItems()).isEqualTo(7);

        verify(cartItemRepository).save(existingItem);
        assertThat(existingItem.getQuantity()).isEqualTo(7);
    }

    @Test
    void addItemToCart_insufficientStock_throwsException() {
        AddToCartRequest request = AddToCartRequest.builder()
                .productId(10L)
                .quantity(15) // available is 10
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(productRepository.findById(10L)).thenReturn(Optional.of(sampleProduct));
        when(inventoryRepository.findByProductId(10L)).thenReturn(Optional.of(sampleInventory));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.addItemToCart(1L, request))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock for product 'SKU-TEST'. Requested: 15, Available: 10");

        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void addItemToCart_upsertMerge_insufficientStock_throwsException() {
        AddToCartRequest request = AddToCartRequest.builder()
                .productId(10L)
                .quantity(5)
                .build();

        CartItem existingItem = CartItem.builder()
                .id(1L)
                .cart(sampleCart)
                .product(sampleProduct)
                .quantity(8) // 8 + 5 = 13 > 10 available
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(productRepository.findById(10L)).thenReturn(Optional.of(sampleProduct));
        when(inventoryRepository.findByProductId(10L)).thenReturn(Optional.of(sampleInventory));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.of(existingItem));

        assertThatThrownBy(() -> cartService.addItemToCart(1L, request))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock for product 'SKU-TEST'. Requested: 13, Available: 10");

        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void updateItemQuantity_success() {
        UpdateCartItemRequest request = UpdateCartItemRequest.builder()
                .quantity(5)
                .build();

        CartItem existingItem = CartItem.builder()
                .id(1L)
                .cart(sampleCart)
                .product(sampleProduct)
                .quantity(2)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.of(existingItem));
        when(inventoryRepository.findByProductId(10L)).thenReturn(Optional.of(sampleInventory)); // 10 available
        when(cartItemRepository.findByCartId(50L)).thenReturn(List.of(existingItem));

        CartResponse response = cartService.updateItemQuantity(1L, 10L, request);

        assertThat(response).isNotNull();
        assertThat(existingItem.getQuantity()).isEqualTo(5);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(5);
        assertThat(response.getSubtotal()).isEqualByComparingTo("250.00");

        verify(cartItemRepository).save(existingItem);
    }

    @Test
    void updateItemQuantity_insufficientStock_throwsException() {
        UpdateCartItemRequest request = UpdateCartItemRequest.builder()
                .quantity(20) // available is 10
                .build();

        CartItem existingItem = CartItem.builder()
                .id(1L)
                .cart(sampleCart)
                .product(sampleProduct)
                .quantity(2)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.of(existingItem));
        when(inventoryRepository.findByProductId(10L)).thenReturn(Optional.of(sampleInventory));

        assertThatThrownBy(() -> cartService.updateItemQuantity(1L, 10L, request))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Requested: 20, Available: 10");

        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void removeItemFromCart_success() {
        CartItem existingItem = CartItem.builder()
                .id(1L)
                .cart(sampleCart)
                .product(sampleProduct)
                .quantity(2)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartIdAndProductId(50L, 10L)).thenReturn(Optional.of(existingItem));
        when(cartItemRepository.findByCartId(50L)).thenReturn(Collections.emptyList());

        CartResponse response = cartService.removeItemFromCart(1L, 10L);

        assertThat(response).isNotNull();
        assertThat(response.getItems()).isEmpty();
        assertThat(response.getSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getTotalItems()).isZero();

        verify(cartItemRepository).delete(existingItem);
    }

    @Test
    void getCart_userNotFound_throwsException() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.getCart(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found with id: '999'");
    }
}
