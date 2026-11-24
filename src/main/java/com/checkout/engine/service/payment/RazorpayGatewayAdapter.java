package com.checkout.engine.service.payment;

import com.checkout.engine.dto.PaymentInitiationRequest;
import com.checkout.engine.dto.PaymentInitiationResult;
import com.checkout.engine.dto.RefundRequest;
import com.checkout.engine.dto.RefundResult;
import com.checkout.engine.entity.PaymentStatus;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Getter
@Component("razorpayGatewayAdapter")
public class RazorpayGatewayAdapter implements PaymentGateway {

    public static final String GATEWAY_NAME = "RAZORPAY";

    @Value("${checkout.payment.razorpay.key-id:${RAZORPAY_KEY_ID:rzp_test_sampleKeyId}}")
    private String keyId;

    @Value("${checkout.payment.razorpay.key-secret:${RAZORPAY_KEY_SECRET:rzp_test_sampleKeySecret}}")
    private String keySecret;

    @Value("${checkout.payment.razorpay.webhook-secret:${RAZORPAY_WEBHOOK_SECRET:rzp_test_sampleWebhookSecret}}")
    private String webhookSecret;

    @Override
    public PaymentInitiationResult initiatePayment(PaymentInitiationRequest request) {
        // Create sandbox payment link reference
        String transactionReference = "plink_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String paymentUrl = buildPaymentUrl(transactionReference);

        log.info("Razorpay adapter created sandbox payment link for order {} (amount: {} {}, keyId: {}): txnRef={}, url={}",
                request.getOrderNumber(), request.getAmount(), request.getCurrency(), maskKey(keyId), transactionReference, paymentUrl);

        return PaymentInitiationResult.builder()
                .transactionReference(transactionReference)
                .paymentUrl(paymentUrl)
                .status(PaymentStatus.INITIATED)
                .gatewayName(GATEWAY_NAME)
                .build();
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        if (signature == null || signature.isBlank() || webhookSecret == null || webhookSecret.isBlank()) {
            return false;
        }

        try {
            String expectedSignature = calculateHmacSha256(payload != null ? payload : "", webhookSecret);
            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.warn("Razorpay webhook signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public RefundResult initiateRefund(RefundRequest request) {
        String refundReference = "rfnd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("Razorpay adapter initiated refund for txnRef {}: amount={}, refundRef={}",
                request.getTransactionReference(), request.getAmount(), refundReference);

        return RefundResult.builder()
                .refundReference(refundReference)
                .transactionReference(request.getTransactionReference())
                .status("SUCCESS")
                .message("Razorpay refund processed")
                .build();
    }

    @Override
    public String getGatewayName() {
        return GATEWAY_NAME;
    }

    @Override
    public String buildPaymentUrl(String transactionReference) {
        return "https://rzp.io/i/" + transactionReference;
    }

    public static String calculateHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) {
            return "***";
        }
        return key.substring(0, 8) + "...";
    }
}
