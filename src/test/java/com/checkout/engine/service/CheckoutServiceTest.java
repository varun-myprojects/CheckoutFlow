package com.checkout.engine.service;

import com.checkout.engine.dto.CheckoutResponse;
import com.checkout.engine.dto.PaymentInitiationResult;
import com.checkout.engine.entity.Cart;
import com.checkout.engine.entity.CartItem;
import com.checkout.engine.entity.CartStatus;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.OrderItem;
import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.entity.PaymentStatus;
import com.checkout.engine.entity.PaymentTransaction;
import com.checkout.engine.entity.Product;
import com.checkout.engine.entity.User;
import com.checkout.engine.exception.DuplicateRequestException;
import com.checkout.engine.exception.InsufficientStockException;
import com.checkout.engine.exception.InvalidStateTransitionException;
import com.checkout.engine.exception.LockAcquisitionException;
import com.checkout.engine.repository.CartItemRepository;
import com.checkout.engine.repository.CartRepository;
import com.checkout.engine.repository.OrderItemRepository;
import com.checkout.engine.repository.OrderRepository;
import com.checkout.engine.repository.PaymentTransactionRepository;
import com.checkout.engine.repository.UserRepository;
import com.checkout.engine.service.cache.CacheService;
import com.checkout.engine.service.lock.InventoryLockStrategy;
import com.checkout.engine.service.payment.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @Mock
    private InventoryLockStrategy inventoryLockStrategy;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private CacheService cacheService;

    @Spy
    private OrderStateMachine orderStateMachine = new OrderStateMachine();

    @Spy
    private CheckoutTransactionRunner checkoutTransactionRunner = new CheckoutTransactionRunner();

    @InjectMocks
    private CheckoutService checkoutService;

    private User sampleUser;
    private Product sampleProduct;
    private Cart sampleCart;
    private CartItem sampleCartItem;

    @BeforeEach
    void setUp() {
        lenient().when(inventoryLockStrategy.executeWithDistributedLock(any(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> action = invocation.getArgument(1);
                    return action.get();
                });

        lenient().when(paymentGateway.initiatePayment(any()))
                .thenReturn(PaymentInitiationResult.builder()
                        .transactionReference("mock_txn_test123")
                        .paymentUrl("http://localhost:8080/api/v1/mock-gateway/pay/mock_txn_test123")
                        .status(PaymentStatus.INITIATED)
                        .gatewayName("MOCK")
                        .build());

        lenient().when(paymentGateway.getGatewayName()).thenReturn("MOCK");

        lenient().when(paymentGateway.buildPaymentUrl(any()))
                .thenAnswer(inv -> "http://localhost:8080/api/v1/mock-gateway/pay/" + inv.getArgument(0));

        lenient().when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        sampleUser = User.builder()
                .id(1L)
                .email("john.doe@example.com")
                .name("John Doe")
                .build();

        sampleProduct = Product.builder()
                .id(10L)
                .sku("SKU-MACBOOK")
                .name("MacBook Pro")
                .price(BigDecimal.valueOf(2000.00))
                .status("ACTIVE")
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
    }

    @Test
    void checkout_success_createsOrderAndSnapshotsPrice() {
        when(orderRepository.findByIdempotencyKey("KEY-123")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartId(50L)).thenReturn(List.of(sampleCartItem));

        Order savedOrder = Order.builder()
                .id(999L)
                .orderNumber("ORD-TEST1234")
                .user(sampleUser)
                .status(OrderStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(4000.00))
                .idempotencyKey("KEY-123")
                .build();
        savedOrder.setCreatedAt(Instant.now());

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        OrderItem savedOrderItem = OrderItem.builder()
                .id(1L)
                .order(savedOrder)
                .product(sampleProduct)
                .quantity(2)
                .price(BigDecimal.valueOf(2000.00))
                .build();

        when(orderItemRepository.save(any(OrderItem.class))).thenReturn(savedOrderItem);

        CheckoutResponse response = checkoutService.checkout(1L, "KEY-123");

        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo(999L);
        assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(response.getTotalAmount()).isEqualByComparingTo("4000.00");
        assertThat(response.getPaymentUrl()).isEqualTo("http://localhost:8080/api/v1/mock-gateway/pay/mock_txn_test123");
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getPrice()).isEqualByComparingTo("2000.00");
        assertThat(response.getItems().get(0).getLineTotal()).isEqualByComparingTo("4000.00");

        // Verify cart transitioned to ORDERED
        assertThat(sampleCart.getStatus()).isEqualTo(CartStatus.ORDERED);
        verify(cartRepository).save(sampleCart);
        verify(inventoryLockStrategy).reserveInventory(any(Order.class), any());

        // Verify PaymentTransaction created in INITIATED status
        ArgumentCaptor<PaymentTransaction> txnCaptor = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentTransactionRepository).save(txnCaptor.capture());
        PaymentTransaction savedTxn = txnCaptor.getValue();
        assertThat(savedTxn.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(savedTxn.getTransactionReference()).isEqualTo("mock_txn_test123");
        assertThat(savedTxn.getAmount()).isEqualByComparingTo("4000.00");
        assertThat(savedTxn.getPaymentMethod()).isEqualTo("MOCK");
    }

    @Test
    void checkout_paddedIdempotencyKey_normalizesAndTrimsKey() {
        when(orderRepository.findByIdempotencyKey("KEY-PADDED")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartId(50L)).thenReturn(List.of(sampleCartItem));

        Order savedOrder = Order.builder()
                .id(999L)
                .orderNumber("ORD-PADDED")
                .user(sampleUser)
                .status(OrderStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(4000.00))
                .idempotencyKey("KEY-PADDED")
                .build();
        savedOrder.setCreatedAt(Instant.now());

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        OrderItem savedOrderItem = OrderItem.builder()
                .id(1L)
                .order(savedOrder)
                .product(sampleProduct)
                .quantity(2)
                .price(BigDecimal.valueOf(2000.00))
                .build();

        when(orderItemRepository.save(any(OrderItem.class))).thenReturn(savedOrderItem);

        CheckoutResponse response = checkoutService.checkout(1L, "   KEY-PADDED   ");

        // Assert trimmed key was looked up and saved in Order entity
        verify(orderRepository).findByIdempotencyKey("KEY-PADDED");
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getIdempotencyKey()).isEqualTo("KEY-PADDED");
        assertThat(response.getIdempotencyKey()).isEqualTo("KEY-PADDED");
    }

    @Test
    void checkout_emptyCart_throwsException() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartId(50L)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> checkoutService.checkout(1L, "KEY-EMPTY"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot checkout: Active cart is empty");

        verify(orderRepository, never()).save(any());
        verify(inventoryLockStrategy, never()).reserveInventory(any(), any());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void checkout_blankIdempotencyKey_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> checkoutService.checkout(1L, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key header must not be blank");

        assertThatThrownBy(() -> checkoutService.checkout(1L, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key header must not be blank");

        assertThatThrownBy(() -> checkoutService.checkout(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key header must not be blank");

        verify(orderRepository, never()).findByIdempotencyKey(any());
        verify(inventoryLockStrategy, never()).reserveInventory(any(), any());
    }

    @Test
    void checkout_idempotencyReplay_returnsExistingOrder() {
        Order existingOrder = Order.builder()
                .id(777L)
                .orderNumber("ORD-IDEMP-EXISTING")
                .user(sampleUser)
                .status(OrderStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(2000.00))
                .idempotencyKey("KEY-REPLAY")
                .build();

        OrderItem item = OrderItem.builder()
                .id(1L)
                .order(existingOrder)
                .product(sampleProduct)
                .quantity(1)
                .price(BigDecimal.valueOf(2000.00))
                .build();

        PaymentTransaction txn = PaymentTransaction.builder()
                .id(10L)
                .order(existingOrder)
                .transactionReference("mock_txn_existing")
                .amount(BigDecimal.valueOf(2000.00))
                .status(PaymentStatus.INITIATED)
                .build();

        when(orderRepository.findByIdempotencyKey("KEY-REPLAY")).thenReturn(Optional.of(existingOrder));
        when(orderItemRepository.findByOrderId(777L)).thenReturn(List.of(item));
        when(paymentTransactionRepository.findByOrderId(777L)).thenReturn(List.of(txn));

        CheckoutResponse response = checkoutService.checkout(1L, "KEY-REPLAY");

        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo(777L);
        assertThat(response.getOrderNumber()).isEqualTo("ORD-IDEMP-EXISTING");
        assertThat(response.getPaymentUrl()).isEqualTo("http://localhost:8080/api/v1/mock-gateway/pay/mock_txn_existing");
        verify(orderRepository, never()).save(any());
        verify(inventoryLockStrategy, never()).reserveInventory(any(), any());
    }

    @Test
    void checkout_idempotencyCollisionAnotherUser_throwsConflict() {
        User anotherUser = User.builder().id(2L).name("Jane").email("jane@example.com").build();
        Order existingOrder = Order.builder()
                .id(777L)
                .user(anotherUser)
                .idempotencyKey("KEY-COLLISION")
                .build();

        when(orderRepository.findByIdempotencyKey("KEY-COLLISION")).thenReturn(Optional.of(existingOrder));

        assertThatThrownBy(() -> checkoutService.checkout(1L, "KEY-COLLISION"))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessageContaining("Idempotency key already used by another user.");

        verify(inventoryLockStrategy, never()).reserveInventory(any(), any());
    }

    @Test
    void checkout_insufficientStock_throwsException() {
        when(orderRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartId(50L)).thenReturn(List.of(sampleCartItem));

        Order savedOrder = Order.builder()
                .id(999L)
                .user(sampleUser)
                .status(OrderStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(4000.00))
                .build();

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        doThrow(new InsufficientStockException("SKU-MACBOOK", 2, 1))
                .when(inventoryLockStrategy).reserveInventory(any(Order.class), any());

        assertThatThrownBy(() -> checkoutService.checkout(1L, "KEY-STOCK"))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Requested: 2, Available: 1");

        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void checkout_optimisticLockConflict_retriesAndSucceeds() {
        when(orderRepository.findByIdempotencyKey("KEY-RETRY")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartId(50L)).thenReturn(List.of(sampleCartItem));

        Order savedOrder = Order.builder()
                .id(999L)
                .orderNumber("ORD-RETRY")
                .user(sampleUser)
                .status(OrderStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(4000.00))
                .idempotencyKey("KEY-RETRY")
                .build();
        savedOrder.setCreatedAt(Instant.now());

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        OrderItem savedOrderItem = OrderItem.builder()
                .id(1L)
                .order(savedOrder)
                .product(sampleProduct)
                .quantity(2)
                .price(BigDecimal.valueOf(2000.00))
                .build();

        when(orderItemRepository.save(any(OrderItem.class))).thenReturn(savedOrderItem);

        // Attempt 1 fails with optimistic lock exception, Attempt 2 succeeds
        when(inventoryLockStrategy.reserveInventory(any(Order.class), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, 10L))
                .thenReturn(Collections.emptyList());

        CheckoutResponse response = checkoutService.checkout(1L, "KEY-RETRY");

        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo(999L);
        assertThat(response.getPaymentUrl()).isEqualTo("http://localhost:8080/api/v1/mock-gateway/pay/mock_txn_test123");
        // Verify reserveInventory was retried (called twice)
        verify(inventoryLockStrategy, times(2)).reserveInventory(any(Order.class), any());
    }

    @Test
    void checkout_exhausts3OuterAttempts_throwsObjectOptimisticLockingFailureException() {
        when(orderRepository.findByIdempotencyKey("KEY-EXHAUST")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));
        when(cartItemRepository.findByCartId(50L)).thenReturn(List.of(sampleCartItem));

        Order savedOrder = Order.builder()
                .id(999L)
                .orderNumber("ORD-EXHAUST")
                .user(sampleUser)
                .status(OrderStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(4000.00))
                .idempotencyKey("KEY-EXHAUST")
                .build();
        savedOrder.setCreatedAt(Instant.now());

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        OrderItem savedOrderItem = OrderItem.builder()
                .id(1L)
                .order(savedOrder)
                .product(sampleProduct)
                .quantity(2)
                .price(BigDecimal.valueOf(2000.00))
                .build();

        when(orderItemRepository.save(any(OrderItem.class))).thenReturn(savedOrderItem);

        when(inventoryLockStrategy.reserveInventory(any(Order.class), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, 10L));

        assertThatThrownBy(() -> checkoutService.checkout(1L, "KEY-EXHAUST"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(inventoryLockStrategy, times(3)).reserveInventory(any(Order.class), any());
    }

    @Test
    void checkout_cartItemsChangedDuringLockAcquisition_throwsLockAcquisitionException() {
        when(orderRepository.findByIdempotencyKey("KEY-CHANGED")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(cartRepository.findByUserIdAndStatus(1L, CartStatus.ACTIVE)).thenReturn(Optional.of(sampleCart));

        // Pre-lookup returns product 10
        // Inside transaction, cart has product 20 (changed concurrently)
        Product changedProduct = Product.builder().id(20L).sku("SKU-CHANGED").price(BigDecimal.TEN).build();
        CartItem changedCartItem = CartItem.builder().id(502L).cart(sampleCart).product(changedProduct).quantity(1).build();

        when(cartItemRepository.findByCartId(50L))
                .thenReturn(List.of(sampleCartItem))
                .thenReturn(List.of(changedCartItem));

        assertThatThrownBy(() -> checkoutService.checkout(1L, "KEY-CHANGED"))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("Cart items changed concurrently during checkout lock acquisition");

        verify(orderRepository, never()).save(any());
        verify(inventoryLockStrategy, never()).reserveInventory(any(), any());
        verify(paymentTransactionRepository, never()).save(any());
    }
}
