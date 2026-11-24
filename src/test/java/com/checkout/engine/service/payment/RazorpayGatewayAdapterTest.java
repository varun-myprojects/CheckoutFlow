package com.checkout.engine.service.payment;

import com.checkout.engine.dto.PaymentInitiationRequest;
import com.checkout.engine.dto.PaymentInitiationResult;
import com.checkout.engine.dto.RefundRequest;
import com.checkout.engine.dto.RefundResult;
import com.checkout.engine.entity.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayGatewayAdapterTest {

    private RazorpayGatewayAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RazorpayGatewayAdapter();
        ReflectionTestUtils.setField(adapter, "keyId", "rzp_test_mockKeyId");
        ReflectionTestUtils.setField(adapter, "keySecret", "rzp_test_mockKeySecret");
        ReflectionTestUtils.setField(adapter, "webhookSecret", "rzp_test_mockWebhookSecret");
    }

    @Test
    void initiatePayment_createsRazorpayLinkAndReturnsResult() {
        PaymentInitiationRequest request = PaymentInitiationRequest.builder()
                .orderId(10L)
                .orderNumber("ORD-RZP-10")
                .amount(BigDecimal.valueOf(99.99))
                .currency("USD")
                .idempotencyKey("pay-rzp-1")
                .customerEmail("customer@example.com")
                .customerName("Customer Name")
                .build();

        PaymentInitiationResult result = adapter.initiatePayment(request);

        assertThat(result).isNotNull();
        assertThat(result.getTransactionReference()).startsWith("plink_");
        assertThat(result.getPaymentUrl()).isEqualTo("https://rzp.io/i/" + result.getTransactionReference());
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(result.getGatewayName()).isEqualTo("RAZORPAY");
    }

    @Test
    void verifyWebhookSignature_validHmac_returnsTrue() {
        String payload = "{\"entity\":\"event\",\"event\":\"payment.captured\"}";
        String validSignature = RazorpayGatewayAdapter.calculateHmacSha256(payload, "rzp_test_mockWebhookSecret");

        assertThat(adapter.verifyWebhookSignature(payload, validSignature)).isTrue();
        assertThat(adapter.verifyWebhookSignature(payload, "invalid_signature")).isFalse();
        assertThat(adapter.verifyWebhookSignature(payload, "")).isFalse();
        assertThat(adapter.verifyWebhookSignature(payload, (String) null)).isFalse();
    }

    @Test
    void initiateRefund_returnsSuccess() {
        RefundRequest request = RefundRequest.builder()
                .transactionReference("plink_1234567890abcdef")
                .amount(BigDecimal.valueOf(99.99))
                .currency("USD")
                .reason("Damaged item")
                .build();

        RefundResult result = adapter.initiateRefund(request);

        assertThat(result).isNotNull();
        assertThat(result.getRefundReference()).startsWith("rfnd_");
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
    }
}
