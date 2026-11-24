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
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.OrderRepository;
import com.checkout.engine.repository.PaymentTransactionRepository;
import com.checkout.engine.service.OrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Component("mockPaymentGatewayAdapter")
@RequiredArgsConstructor
public class MockPaymentGatewayAdapter implements PaymentGateway {

    public static final String GATEWAY_NAME = "MOCK";
    private static final String DEFAULT_MOCK_SECRET = "mock_webhook_secret_key";

    @Value("${checkout.payment.mock.base-url:http://localhost:8080}")
    private String baseUrl = "http://localhost:8080";

    @Value("${checkout.payment.mock.webhook-secret:mock_webhook_secret_key}")
    private String webhookSecret = DEFAULT_MOCK_SECRET;

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final OrderRepository orderRepository;
    private final OrderStateMachine orderStateMachine;

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
     * Deterministically simulates gateway webhook callbacks with support for SUCCESS, FAILURE,
     * artificial latency, and duplicate webhook replays.
     */
    @Transactional
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
        if (request.getTransactionReference() != null && !request.getTransactionReference().isBlank()) {
            transaction = paymentTransactionRepository.findByTransactionReference(request.getTransactionReference())
                    .orElseThrow(() -> new ResourceNotFoundException("PaymentTransaction", "transactionReference", request.getTransactionReference()));
        } else if (request.getOrderId() != null) {
            var txns = paymentTransactionRepository.findByOrderId(request.getOrderId());
            if (txns.isEmpty()) {
                throw new ResourceNotFoundException("PaymentTransaction", "orderId", request.getOrderId());
            }
            transaction = txns.get(txns.size() - 1);
        } else if (request.getOrderNumber() != null && !request.getOrderNumber().isBlank()) {
            Order ord = orderRepository.findByOrderNumber(request.getOrderNumber())
                    .orElseThrow(() -> new ResourceNotFoundException("Order", "orderNumber", request.getOrderNumber()));
            var txns = paymentTransactionRepository.findByOrderId(ord.getId());
            if (txns.isEmpty()) {
                throw new ResourceNotFoundException("PaymentTransaction", "orderId", ord.getId());
            }
            transaction = txns.get(txns.size() - 1);
        } else {
            throw new IllegalArgumentException("Either transactionReference, orderId, or orderNumber must be provided");
        }

        Order order = transaction.getOrder();
        boolean isSuccess = "SUCCESS".equalsIgnoreCase(request.getEffectiveStatus());
        int replays = request.getReplayCount() != null && request.getReplayCount() > 0 ? request.getReplayCount() : 1;

        for (int i = 0; i < replays; i++) {
            if (isSuccess) {
                if (transaction.getStatus() != PaymentStatus.CAPTURED) {
                    transaction.setStatus(PaymentStatus.CAPTURED);
                    paymentTransactionRepository.save(transaction);
                }
                if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                    orderStateMachine.validateTransition(order.getStatus(), OrderStatus.CONFIRMED);
                    order.setStatus(OrderStatus.CONFIRMED);
                    orderRepository.save(order);
                }
            } else {
                if (transaction.getStatus() != PaymentStatus.FAILED) {
                    transaction.setStatus(PaymentStatus.FAILED);
                    paymentTransactionRepository.save(transaction);
                }
                if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                    orderStateMachine.validateTransition(order.getStatus(), OrderStatus.FAILED);
                    order.setStatus(OrderStatus.FAILED);
                    orderRepository.save(order);
                }
            }
        }

        String webhookEvent = isSuccess ? "payment.captured" : "payment.failed";
        String payload = String.format("{\"event\":\"%s\",\"transactionReference\":\"%s\",\"orderNumber\":\"%s\",\"status\":\"%s\"}",
                webhookEvent, transaction.getTransactionReference(), order.getOrderNumber(), transaction.getStatus());
        String signature = calculateHmacSha256(payload, webhookSecret);

        log.info("Simulated mock webhook (replays={}): txnRef={}, paymentStatus={}, orderStatus={}",
                replays, transaction.getTransactionReference(), transaction.getStatus(), order.getStatus());

        return MockWebhookSimulationResponse.builder()
                .transactionReference(transaction.getTransactionReference())
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .paymentStatus(transaction.getStatus())
                .orderStatus(order.getStatus())
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
