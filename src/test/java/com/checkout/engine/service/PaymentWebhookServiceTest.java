package com.checkout.engine.service;

import com.checkout.engine.dto.PaymentWebhookResult;
import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.InventoryReservation;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.entity.PaymentStatus;
import com.checkout.engine.entity.PaymentTransaction;
import com.checkout.engine.entity.Product;
import com.checkout.engine.entity.ProcessedWebhookEvent;
import com.checkout.engine.entity.ReservationStatus;
import com.checkout.engine.exception.InvalidWebhookSignatureException;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.InventoryReservationRepository;
import com.checkout.engine.repository.OrderRepository;
import com.checkout.engine.repository.PaymentTransactionRepository;
import com.checkout.engine.repository.ProcessedWebhookEventRepository;
import com.checkout.engine.service.payment.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceTest {

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private ProcessedWebhookEventRepository processedWebhookEventRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @Mock
    private InventoryReservationRepository inventoryReservationRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Spy
    private OrderStateMachine orderStateMachine = new OrderStateMachine();

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PaymentWebhookService paymentWebhookService;

    private Order sampleOrder;
    private PaymentTransaction sampleTransaction;
    private Product sampleProduct;
    private Inventory sampleInventory;
    private InventoryReservation sampleReservation;

    @BeforeEach
    void setUp() {
        sampleOrder = Order.builder()
                .id(1L)
                .orderNumber("ORD-TEST-001")
                .status(OrderStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(99.99))
                .build();

        sampleTransaction = PaymentTransaction.builder()
                .id(10L)
                .order(sampleOrder)
                .transactionReference("txn_12345")
                .amount(BigDecimal.valueOf(99.99))
                .status(PaymentStatus.INITIATED)
                .build();

        sampleProduct = Product.builder()
                .id(100L)
                .sku("PROD-100")
                .name("Test Product")
                .price(BigDecimal.valueOf(99.99))
                .build();

        sampleInventory = Inventory.builder()
                .id(50L)
                .product(sampleProduct)
                .availableQty(10)
                .reservedQty(2)
                .build();

        sampleReservation = InventoryReservation.builder()
                .id(500L)
                .order(sampleOrder)
                .product(sampleProduct)
                .quantity(2)
                .status(ReservationStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }

    @Test
    void processWebhook_invalidSignature_throwsInvalidWebhookSignatureException() {
        when(paymentGateway.verifyWebhookSignature(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> paymentWebhookService.processWebhook("{}", "bad_sig"))
                .isInstanceOf(InvalidWebhookSignatureException.class)
                .hasMessageContaining("Invalid webhook signature");

        verify(orderRepository, never()).save(any());
        verify(processedWebhookEventRepository, never()).save(any());
    }

    @Test
    void processWebhook_idempotentDuplicateEvent_returns200WithoutMutatingState() {
        when(paymentGateway.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);
        when(processedWebhookEventRepository.existsByProviderEventId("evt_dup_123")).thenReturn(true);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(paymentTransactionRepository.findByOrderId(1L)).thenReturn(List.of(sampleTransaction));

        String payload = "{\"providerEventId\":\"evt_dup_123\",\"orderId\":1,\"status\":\"SUCCESS\"}";

        PaymentWebhookResult result = paymentWebhookService.processWebhook(payload, "valid_sig");

        assertThat(result.isAlreadyProcessed()).isTrue();
        assertThat(result.getProviderEventId()).isEqualTo("evt_dup_123");
        assertThat(result.getOrderStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        verify(orderRepository, never()).findByIdWithPessimisticLock(any());
        verify(orderRepository, never()).save(any());
        verify(inventoryReservationRepository, never()).save(any());
    }

    @Test
    void processWebhook_paymentSuccess_transitionsToConfirmed_consumesReservations_decrementsReservedQty() {
        when(paymentGateway.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);
        when(processedWebhookEventRepository.existsByProviderEventId("evt_succ_123")).thenReturn(false);
        when(orderRepository.findByIdWithPessimisticLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(paymentTransactionRepository.findByOrderId(1L)).thenReturn(List.of(sampleTransaction));
        when(inventoryReservationRepository.findByOrderIdAndStatus(1L, ReservationStatus.ACTIVE))
                .thenReturn(List.of(sampleReservation));
        when(inventoryRepository.findByProductIdWithPessimisticLock(100L)).thenReturn(Optional.of(sampleInventory));

        String payload = "{\"providerEventId\":\"evt_succ_123\",\"orderId\":1,\"event\":\"payment.captured\",\"status\":\"SUCCESS\"}";

        PaymentWebhookResult result = paymentWebhookService.processWebhook(payload, "valid_sig");

        assertThat(result.isAlreadyProcessed()).isFalse();
        assertThat(result.getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.CAPTURED);

        // Verify entities updated
        assertThat(sampleOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(sampleTransaction.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(sampleReservation.getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(sampleInventory.getReservedQty()).isEqualTo(0); // 2 - 2 = 0
        assertThat(sampleInventory.getAvailableQty()).isEqualTo(10); // remains 10

        verify(orderRepository).save(sampleOrder);
        verify(paymentTransactionRepository).save(sampleTransaction);
        verify(inventoryReservationRepository).save(sampleReservation);
        verify(inventoryRepository).save(sampleInventory);
        verify(processedWebhookEventRepository).saveAndFlush(any(ProcessedWebhookEvent.class));
    }

    @Test
    void processWebhook_paymentFailed_transitionsToFailed_releasesReservations_restoresAvailableQty() {
        when(paymentGateway.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);
        when(processedWebhookEventRepository.existsByProviderEventId("evt_fail_123")).thenReturn(false);
        when(orderRepository.findByIdWithPessimisticLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(paymentTransactionRepository.findByOrderId(1L)).thenReturn(List.of(sampleTransaction));
        when(inventoryReservationRepository.findByOrderIdAndStatus(1L, ReservationStatus.ACTIVE))
                .thenReturn(List.of(sampleReservation));
        when(inventoryRepository.findByProductIdWithPessimisticLock(100L)).thenReturn(Optional.of(sampleInventory));

        String payload = "{\"providerEventId\":\"evt_fail_123\",\"orderId\":1,\"event\":\"payment.failed\",\"status\":\"FAILURE\"}";

        PaymentWebhookResult result = paymentWebhookService.processWebhook(payload, "valid_sig");

        assertThat(result.isAlreadyProcessed()).isFalse();
        assertThat(result.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);

        // Verify entities updated
        assertThat(sampleOrder.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(sampleTransaction.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(sampleReservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(sampleInventory.getAvailableQty()).isEqualTo(12); // 10 + 2 = 12
        assertThat(sampleInventory.getReservedQty()).isEqualTo(0); // 2 - 2 = 0

        verify(orderRepository).save(sampleOrder);
        verify(paymentTransactionRepository).save(sampleTransaction);
        verify(inventoryReservationRepository).save(sampleReservation);
        verify(inventoryRepository).save(sampleInventory);
        verify(processedWebhookEventRepository).saveAndFlush(any(ProcessedWebhookEvent.class));
    }

    @Test
    void processWebhook_missingProviderEventId_throwsIllegalArgumentException() {
        when(paymentGateway.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);

        String payload = "{\"orderId\":1,\"status\":\"SUCCESS\"}";

        assertThatThrownBy(() -> paymentWebhookService.processWebhook(payload, "valid_sig"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing provider_event_id");
    }

    @Test
    void processWebhook_latePaymentForExpiredOrder_initiatesRefundWithoutModifyingInventory() {
        sampleOrder.setStatus(OrderStatus.EXPIRED);
        when(paymentGateway.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);
        when(processedWebhookEventRepository.existsByProviderEventId("evt_late_123")).thenReturn(false);
        when(orderRepository.findByIdWithPessimisticLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(paymentTransactionRepository.findByOrderId(1L)).thenReturn(List.of(sampleTransaction));

        String payload = "{\"providerEventId\":\"evt_late_123\",\"orderId\":1,\"event\":\"payment.captured\",\"status\":\"SUCCESS\"}";

        PaymentWebhookResult result = paymentWebhookService.processWebhook(payload, "valid_sig");

        assertThat(result.isAlreadyProcessed()).isFalse();
        assertThat(result.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.LATE_PAYMENT_REFUND_PENDING);

        // Order remains EXPIRED, no inventory mutations
        assertThat(sampleOrder.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(sampleTransaction.getStatus()).isEqualTo(PaymentStatus.LATE_PAYMENT_REFUND_PENDING);

        verify(orderRepository, never()).save(sampleOrder);
        verify(inventoryRepository, never()).save(any());
        verify(inventoryReservationRepository, never()).save(any());
        verify(paymentGateway).initiateRefund(any());
        verify(processedWebhookEventRepository).saveAndFlush(any(ProcessedWebhookEvent.class));
    }
}
