package com.checkout.engine.service.payment;

import com.checkout.engine.dto.MockWebhookSimulationRequest;
import com.checkout.engine.dto.MockWebhookSimulationResponse;
import com.checkout.engine.dto.PaymentInitiationRequest;
import com.checkout.engine.dto.PaymentInitiationResult;
import com.checkout.engine.dto.RefundRequest;
import com.checkout.engine.dto.RefundResult;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.PaymentStatus;
import com.checkout.engine.entity.PaymentTransaction;
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.OrderRepository;
import com.checkout.engine.repository.PaymentTransactionRepository;
import com.checkout.engine.service.PaymentWebhookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Component("mockPaymentGatewayAdapter")
public class MockPaymentGatewayAdapter implements PaymentGateway {

    public static final String GATEWAY_NAME = "MOCK";
    private static final String DEFAULT_MOCK_SECRET = "mock_webhook_secret_key";

    @Value("${checkout.payment.mock.base-url:http://localhost:8080}")
    private String baseUrl = "http://localhost:8080";

    @Value("${checkout.payment.mock.webhook-secret:mock_webhook_secret_key}")
    private String webhookSecret = DEFAULT_MOCK_SECRET;

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final OrderRepository orderRepository;
    private final PaymentWebhookService paymentWebhookService;

    public MockPaymentGatewayAdapter(
            PaymentTransactionRepository paymentTransactionRepository,
            OrderRepository orderRepository,
            @Lazy PaymentWebhookService paymentWebhookService
    ) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.orderRepository = orderRepository;
        this.paymentWebhookService = paymentWebhookService;
    }

    @Override
    public PaymentInitiationResult initiatePayment(PaymentInitiationRequest request) {
        String transactionReference = "mock_txn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String paymentUrl = buildPaymentUrl(transactionReference);

        log.info("Mock gateway initiated payment for order {} (amount: {} {}): txnRef={}, url={}",
                request.getOrderNumber(), request.getAmount(), request.getCurrency(), transactionReference, paymentUrl);

        return PaymentInitiationResult.builder()
                .transactionReference(transactionReference)
                .paymentUrl(paymentUrl)
                .status(PaymentStatus.INITIATED)
                .gatewayName(GATEWAY_NAME)
                .build();
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }

        // Allow explicit test signature or compute HMAC-SHA256
        if ("valid_mock_signature".equals(signature)) {
            return true;
        }

        try {
            String expected = calculateHmacSha256(payload != null ? payload : "", webhookSecret);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.warn("Mock webhook signature verification error: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public RefundResult initiateRefund(RefundRequest request) {
        String refundReference = "mock_ref_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("Mock gateway initiated refund for txnRef {}: amount={}, refundRef={}",
                request.getTransactionReference(), request.getAmount(), refundReference);

        return RefundResult.builder()
                .refundReference(refundReference)
                .transactionReference(request.getTransactionReference())
                .status("SUCCESS")
                .message("Mock refund processed successfully")
                .build();
    }

    @Override
    public String getGatewayName() {
        return GATEWAY_NAME;
    }

    @Override
    public String buildPaymentUrl(String transactionReference) {
        return baseUrl + "/api/v1/mock-gateway/pay/" + transactionReference;
    }

    /**
     * Deterministically simulates gateway webhook callbacks without holding a DB connection.
     * Generates a signed webhook payload with a consistent providerEventId across the replayCount loop
     * and dispatches each attempt through PaymentWebhookService so tests verify real HMAC verification,
     * idempotency table persistence, and inventory reservation consumption/release.
     */
    public MockWebhookSimulationResponse simulateWebhook(MockWebhookSimulationRequest request) {
        Long delay = request.getEffectiveLatencyMs();
        if (delay != null && delay > 0) {
            try {
                log.info("Simulating artificial gateway latency: {} ms", delay);
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during mock webhook simulation latency", e);
            }
        }

        PaymentTransaction transaction;
        Order order;
        if (request.getTransactionReference() != null && !request.getTransactionReference().isBlank()) {
            transaction = paymentTransactionRepository.findByTransactionReference(request.getTransactionReference())
                    .orElseThrow(() -> new ResourceNotFoundException("PaymentTransaction", "transactionReference", request.getTransactionReference()));
            order = orderRepository.findById(transaction.getOrder().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Order", "id", transaction.getOrder().getId()));
        } else if (request.getOrderId() != null) {
            order = orderRepository.findById(request.getOrderId())
                    .orElseThrow(() -> new ResourceNotFoundException("Order", "id", request.getOrderId()));
            var txns = paymentTransactionRepository.findByOrderId(request.getOrderId());
            if (txns.isEmpty()) {
                throw new ResourceNotFoundException("PaymentTransaction", "orderId", request.getOrderId());
            }
            transaction = txns.get(txns.size() - 1);
        } else if (request.getOrderNumber() != null && !request.getOrderNumber().isBlank()) {
            order = orderRepository.findByOrderNumber(request.getOrderNumber())
                    .orElseThrow(() -> new ResourceNotFoundException("Order", "orderNumber", request.getOrderNumber()));
            var txns = paymentTransactionRepository.findByOrderId(order.getId());
            if (txns.isEmpty()) {
                throw new ResourceNotFoundException("PaymentTransaction", "orderId", order.getId());
            }
            transaction = txns.get(txns.size() - 1);
        } else {
            throw new IllegalArgumentException("Either transactionReference, orderId, or orderNumber must be provided");
        }
        boolean isSuccess = "SUCCESS".equalsIgnoreCase(request.getEffectiveStatus());
        int replays = request.getReplayCount() != null && request.getReplayCount() > 0 ? request.getReplayCount() : 1;

        String providerEventId = "mock_evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String webhookEvent = isSuccess ? "payment.captured" : "payment.failed";
        String status = isSuccess ? "SUCCESS" : "FAILURE";

        String payload = String.format(
                "{\"providerEventId\":\"%s\",\"event\":\"%s\",\"transactionReference\":\"%s\",\"orderNumber\":\"%s\",\"orderId\":%d,\"status\":\"%s\"}",
                providerEventId, webhookEvent, transaction.getTransactionReference(), order.getOrderNumber(), order.getId(), status
        );
        String signature = calculateHmacSha256(payload, webhookSecret);

        for (int i = 0; i < replays; i++) {
            paymentWebhookService.processWebhook(payload, signature);
        }

        // Fetch refreshed entities from repository
        Order updatedOrder = orderRepository.findById(order.getId()).orElse(order);
        PaymentTransaction updatedTxn = paymentTransactionRepository.findById(transaction.getId()).orElse(transaction);

        log.info("Simulated mock webhook (replays={}): txnRef={}, paymentStatus={}, orderStatus={}",
                replays, updatedTxn.getTransactionReference(), updatedTxn.getStatus(), updatedOrder.getStatus());

        return MockWebhookSimulationResponse.builder()
                .transactionReference(updatedTxn.getTransactionReference())
                .orderId(updatedOrder.getId())
                .orderNumber(updatedOrder.getOrderNumber())
                .paymentStatus(updatedTxn.getStatus())
                .orderStatus(updatedOrder.getStatus())
                .webhookEvent(webhookEvent)
                .signature(signature)
                .processedCount(replays)
                .message("Mock webhook simulated successfully")
                .build();
    }

    public static String calculateHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", e);
        }
    }
}
