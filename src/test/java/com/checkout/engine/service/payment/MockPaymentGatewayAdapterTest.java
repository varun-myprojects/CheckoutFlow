package com.checkout.engine.service.payment;

import com.checkout.engine.dto.MockWebhookSimulationRequest;
import com.checkout.engine.dto.MockWebhookSimulationResponse;
import com.checkout.engine.dto.PaymentInitiationRequest;
import com.checkout.engine.dto.PaymentInitiationResult;
import com.checkout.engine.dto.RefundRequest;
import com.checkout.engine.dto.RefundResult;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.entity.PaymentStatus;
import com.checkout.engine.entity.PaymentTransaction;
import com.checkout.engine.repository.OrderRepository;
import com.checkout.engine.repository.PaymentTransactionRepository;
import com.checkout.engine.service.PaymentWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MockPaymentGatewayAdapterTest {

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentWebhookService paymentWebhookService;

    @InjectMocks
    private MockPaymentGatewayAdapter adapter;

    private Order sampleOrder;
    private PaymentTransaction sampleTransaction;

    @BeforeEach
    void setUp() {
        sampleOrder = Order.builder()
                .id(100L)
                .orderNumber("ORD-MOCK-100")
                .status(OrderStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(150.00))
                .build();

        sampleTransaction = PaymentTransaction.builder()
                .id(200L)
                .order(sampleOrder)
                .transactionReference("mock_txn_1234567890abcdef")
                .amount(BigDecimal.valueOf(150.00))
                .status(PaymentStatus.INITIATED)
                .paymentMethod("MOCK")
                .build();
    }

    @Test
    void initiatePayment_createsMockTransactionReferenceAndUrl() {
        PaymentInitiationRequest request = PaymentInitiationRequest.builder()
                .orderId(100L)
                .orderNumber("ORD-MOCK-100")
                .amount(BigDecimal.valueOf(150.00))
                .currency("USD")
                .idempotencyKey("pay-key-1")
                .customerEmail("user@example.com")
                .build();

        PaymentInitiationResult result = adapter.initiatePayment(request);

        assertThat(result).isNotNull();
        assertThat(result.getTransactionReference()).startsWith("mock_txn_");
        assertThat(result.getPaymentUrl()).contains(result.getTransactionReference());
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(result.getGatewayName()).isEqualTo("MOCK");
    }

    @Test
    void verifyWebhookSignature_validTokenAndHmac() {
        assertThat(adapter.verifyWebhookSignature("{}", "valid_mock_signature")).isTrue();
        assertThat(adapter.verifyWebhookSignature("{}", "")).isFalse();
        assertThat(adapter.verifyWebhookSignature("{}", (String) null)).isFalse();

        String payload = "{\"event\":\"payment.captured\"}";
        String validHmac = MockPaymentGatewayAdapter.calculateHmacSha256(payload, "mock_webhook_secret_key");
        assertThat(adapter.verifyWebhookSignature(payload, validHmac)).isTrue();
        assertThat(adapter.verifyWebhookSignature(payload, "invalid_hmac")).isFalse();
    }

    @Test
    void initiateRefund_returnsSuccess() {
        RefundRequest request = RefundRequest.builder()
                .transactionReference("mock_txn_1234567890abcdef")
                .amount(BigDecimal.valueOf(150.00))
                .currency("USD")
                .reason("Customer requested refund")
                .build();

        RefundResult result = adapter.initiateRefund(request);

        assertThat(result).isNotNull();
        assertThat(result.getRefundReference()).startsWith("mock_ref_");
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void simulateWebhook_dispatchesToPaymentWebhookService() {
        when(paymentTransactionRepository.findByTransactionReference("mock_txn_1234567890abcdef"))
                .thenReturn(Optional.of(sampleTransaction));
        when(orderRepository.findById(100L)).thenReturn(Optional.of(sampleOrder));
        when(paymentTransactionRepository.findById(200L)).thenReturn(Optional.of(sampleTransaction));

        MockWebhookSimulationRequest request = MockWebhookSimulationRequest.builder()
                .transactionReference("mock_txn_1234567890abcdef")
                .status("SUCCESS")
                .latencyMs(0L)
                .replayCount(1)
                .build();

        MockWebhookSimulationResponse response = adapter.simulateWebhook(request);

        assertThat(response.getTransactionReference()).isEqualTo("mock_txn_1234567890abcdef");
        assertThat(response.getSignature()).isNotBlank();
        assertThat(response.getProcessedCount()).isEqualTo(1);

        verify(paymentWebhookService).processWebhook(anyString(), anyString());
    }

    @Test
    void simulateWebhook_replays_dispatchesMultipleTimesWithSamePayload() {
        when(paymentTransactionRepository.findByTransactionReference("mock_txn_1234567890abcdef"))
                .thenReturn(Optional.of(sampleTransaction));
        when(orderRepository.findById(100L)).thenReturn(Optional.of(sampleOrder));
        when(paymentTransactionRepository.findById(200L)).thenReturn(Optional.of(sampleTransaction));

        MockWebhookSimulationRequest request = MockWebhookSimulationRequest.builder()
                .transactionReference("mock_txn_1234567890abcdef")
                .status("SUCCESS")
                .latencyMs(0L)
                .replayCount(3)
                .build();

        MockWebhookSimulationResponse response = adapter.simulateWebhook(request);

        assertThat(response.getProcessedCount()).isEqualTo(3);
        verify(paymentWebhookService, times(3)).processWebhook(anyString(), anyString());
    }
}
