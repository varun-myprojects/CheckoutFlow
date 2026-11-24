package com.checkout.engine.service;

import com.checkout.engine.dto.PaymentWebhookPayload;
import com.checkout.engine.dto.PaymentWebhookResult;
import com.checkout.engine.dto.RefundRequest;
import com.checkout.engine.dto.RefundResult;
import com.checkout.engine.entity.Inventory;
import com.checkout.engine.entity.InventoryReservation;
import com.checkout.engine.entity.Order;
import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.entity.PaymentStatus;
import com.checkout.engine.entity.PaymentTransaction;
import com.checkout.engine.entity.ProcessedWebhookEvent;
import com.checkout.engine.entity.ReservationStatus;
import com.checkout.engine.exception.InvalidWebhookSignatureException;
import com.checkout.engine.exception.ResourceNotFoundException;
import com.checkout.engine.repository.InventoryRepository;
import com.checkout.engine.repository.InventoryReservationRepository;
import com.checkout.engine.repository.OrderRepository;
import com.checkout.engine.repository.PaymentTransactionRepository;
import com.checkout.engine.repository.ProcessedWebhookEventRepository;
import com.checkout.engine.service.payment.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    private final PaymentGateway paymentGateway;
    private final ProcessedWebhookEventRepository processedWebhookEventRepository;
    private final OrderRepository orderRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderStateMachine orderStateMachine;
    private final ObjectMapper objectMapper;

    /**
     * Processes an incoming payment webhook callback:
     * 1. Verifies the HMAC-SHA256 signature against the raw payload before parsing.
     * 2. Enforces idempotency via unique providerEventId in processed_webhook_events.
     * 3. On PAYMENT_SUCCESS: locks order row, marks payment CAPTURED, transitions order to CONFIRMED,
     *    marks active reservations CONSUMED, and decrements reserved_qty.
     * 4. On PAYMENT_FAILED: transitions order to FAILED, marks payment FAILED, marks reservations RELEASED,
     *    and restores available_qty.
     */
    @Transactional
    public PaymentWebhookResult processWebhook(String rawPayload, String signature) {
        log.info("Processing webhook callback with signature length {}", signature != null ? signature.length() : 0);

        // 1. Verify HMAC-SHA256 signature before parsing
        if (!paymentGateway.verifyWebhookSignature(rawPayload, signature)) {
            log.warn("Webhook signature verification failed");
            throw new InvalidWebhookSignatureException("Invalid webhook signature");
        }

        // 2. Parse payload
        PaymentWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawPayload, PaymentWebhookPayload.class);
        } catch (Exception e) {
            log.warn("Failed to parse webhook JSON payload: {}", e.getMessage());
            throw new IllegalArgumentException("Malformed JSON webhook payload", e);
        }

        String providerEventId = payload.getEffectiveProviderEventId();
        if (providerEventId == null || providerEventId.isBlank()) {
            throw new IllegalArgumentException("Missing provider_event_id in webhook payload");
        }

        // 3. Idempotency Check
        if (processedWebhookEventRepository.existsByProviderEventId(providerEventId)) {
            log.info("Webhook event {} already processed. Returning idempotent 200 OK.", providerEventId);
            Order existingOrder = resolveOrder(payload, false);
            PaymentStatus paymentStatus = resolvePaymentStatus(existingOrder);

            return PaymentWebhookResult.builder()
                    .providerEventId(providerEventId)
                    .status("SUCCESS")
                    .message("Event already processed")
                    .alreadyProcessed(true)
                    .orderStatus(existingOrder != null ? existingOrder.getStatus() : null)
                    .paymentStatus(paymentStatus)
                    .build();
        }

        // 4. Resolve Order with Pessimistic Lock
        Order order = resolveOrder(payload, true);

        // 5. Handle State Changes based on Outcome
        boolean isSuccess = payload.isSuccess();
        boolean isFailure = payload.isFailure();

        if (isSuccess) {
            handlePaymentSuccess(order);
        } else if (isFailure) {
            handlePaymentFailed(order);
        } else {
            log.info("Webhook event {} received for order {} with unhandled status: {}",
                    providerEventId, order.getOrderNumber(), payload.getStatus());
        }

        // 6. Record Processed Event for Idempotency
        try {
            ProcessedWebhookEvent eventRecord = ProcessedWebhookEvent.builder()
                    .providerEventId(providerEventId)
                    .eventType(payload.getEvent() != null ? payload.getEvent() : payload.getEventType())
                    .payload(rawPayload)
                    .status("PROCESSED")
                    .build();
            processedWebhookEventRepository.saveAndFlush(eventRecord);
        } catch (DataIntegrityViolationException dive) {
            log.info("Concurrent webhook event {} caught by unique constraint; returning idempotent OK", providerEventId);
        }

        PaymentStatus finalPaymentStatus = resolvePaymentStatus(order);

        return PaymentWebhookResult.builder()
                .providerEventId(providerEventId)
                .status("SUCCESS")
                .message("Webhook processed successfully")
                .alreadyProcessed(false)
                .orderStatus(order.getStatus())
                .paymentStatus(finalPaymentStatus)
                .build();
    }

    private void handlePaymentSuccess(Order order) {
        log.info("Handling PAYMENT_SUCCESS for order {}", order.getOrderNumber());

        if (order.getStatus() == OrderStatus.EXPIRED) {
            log.warn("Late payment race condition: order {} is already EXPIRED. Initiating refund without confirming order or overselling inventory.",
                    order.getOrderNumber());
            handleLatePaymentRefund(order);
            return;
        }

        if (order.getStatus() == OrderStatus.CONFIRMED) {
            log.info("Order {} is already CONFIRMED. Skipping transition.", order.getOrderNumber());
            return;
        }

        // Verify status is PENDING_PAYMENT
        orderStateMachine.validateTransition(order.getStatus(), OrderStatus.CONFIRMED);

        // Mark payment transactions as CAPTURED
        List<PaymentTransaction> transactions = paymentTransactionRepository.findByOrderId(order.getId());
        for (PaymentTransaction txn : transactions) {
            if (txn.getStatus() != PaymentStatus.CAPTURED) {
                txn.setStatus(PaymentStatus.CAPTURED);
                paymentTransactionRepository.save(txn);
            }
        }

        // Transition order to CONFIRMED
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        // Mark active inventory reservations as CONSUMED and decrement reserved_qty
        List<InventoryReservation> activeReservations = inventoryReservationRepository
                .findByOrderIdAndStatus(order.getId(), ReservationStatus.ACTIVE);

        // Sort reservations by product ID ascending to prevent deadlock on multi-product orders
        activeReservations.stream()
                .sorted(Comparator.comparing(r -> r.getProduct().getId()))
                .forEach(res -> {
                    res.setStatus(ReservationStatus.CONSUMED);
                    inventoryReservationRepository.save(res);

                    Long productId = res.getProduct().getId();
                    Inventory inventory = inventoryRepository.findByProductIdWithPessimisticLock(productId)
                            .orElseThrow(() -> new ResourceNotFoundException("Inventory", "productId", productId));

                    int newReserved = Math.max(0, inventory.getReservedQty() - res.getQuantity());
                    inventory.setReservedQty(newReserved);
                    inventoryRepository.save(inventory);

                    log.info("Consumed reservation {} for order {}: product={}, reservedQty decremented by {} to {}",
                            res.getId(), order.getOrderNumber(), productId, res.getQuantity(), newReserved);
                });
    }

    private void handlePaymentFailed(Order order) {
        log.info("Handling PAYMENT_FAILED for order {}", order.getOrderNumber());

        if (order.getStatus() == OrderStatus.FAILED) {
            log.info("Order {} is already FAILED. Skipping transition.", order.getOrderNumber());
            return;
        }

        // Transition order to FAILED
        orderStateMachine.validateTransition(order.getStatus(), OrderStatus.FAILED);
        order.setStatus(OrderStatus.FAILED);
        orderRepository.save(order);

        // Mark payment transactions as FAILED
        List<PaymentTransaction> transactions = paymentTransactionRepository.findByOrderId(order.getId());
        for (PaymentTransaction txn : transactions) {
            if (txn.getStatus() != PaymentStatus.FAILED) {
                txn.setStatus(PaymentStatus.FAILED);
                paymentTransactionRepository.save(txn);
            }
        }

        // Mark active reservations as RELEASED and restore available_qty
        List<InventoryReservation> activeReservations = inventoryReservationRepository
                .findByOrderIdAndStatus(order.getId(), ReservationStatus.ACTIVE);

        // Sort reservations by product ID ascending to prevent deadlock
        activeReservations.stream()
                .sorted(Comparator.comparing(r -> r.getProduct().getId()))
                .forEach(res -> {
                    res.setStatus(ReservationStatus.RELEASED);
                    inventoryReservationRepository.save(res);

                    Long productId = res.getProduct().getId();
                    Inventory inventory = inventoryRepository.findByProductIdWithPessimisticLock(productId)
                            .orElseThrow(() -> new ResourceNotFoundException("Inventory", "productId", productId));

                    int newAvailable = inventory.getAvailableQty() + res.getQuantity();
                    int newReserved = Math.max(0, inventory.getReservedQty() - res.getQuantity());
                    inventory.setAvailableQty(newAvailable);
                    inventory.setReservedQty(newReserved);
                    inventoryRepository.save(inventory);

                    log.info("Released reservation {} for order {}: product={}, availableQty restored to {}, reservedQty to {}",
                            res.getId(), order.getOrderNumber(), productId, newAvailable, newReserved);
                });
    }

    private Order resolveOrder(PaymentWebhookPayload payload, boolean withLock) {
        if (payload.getOrderId() != null) {
            return withLock
                    ? orderRepository.findByIdWithPessimisticLock(payload.getOrderId())
                            .orElseThrow(() -> new ResourceNotFoundException("Order", "id", payload.getOrderId()))
                    : orderRepository.findById(payload.getOrderId())
                            .orElseThrow(() -> new ResourceNotFoundException("Order", "id", payload.getOrderId()));
        }

        if (payload.getOrderNumber() != null && !payload.getOrderNumber().isBlank()) {
            String ordNum = payload.getOrderNumber().trim();
            return withLock
                    ? orderRepository.findByOrderNumberWithPessimisticLock(ordNum)
                            .orElseThrow(() -> new ResourceNotFoundException("Order", "orderNumber", ordNum))
                    : orderRepository.findByOrderNumber(ordNum)
                            .orElseThrow(() -> new ResourceNotFoundException("Order", "orderNumber", ordNum));
        }

        String txnRef = payload.getTransactionReference() != null ? payload.getTransactionReference().trim()
                : (payload.getPaymentId() != null ? payload.getPaymentId().trim() : null);

        if (txnRef != null && !txnRef.isBlank()) {
            PaymentTransaction txn = paymentTransactionRepository.findByTransactionReference(txnRef)
                    .orElseThrow(() -> new ResourceNotFoundException("PaymentTransaction", "transactionReference", txnRef));
            Long orderId = txn.getOrder().getId();
            return withLock
                    ? orderRepository.findByIdWithPessimisticLock(orderId)
                            .orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId))
                    : txn.getOrder();
        }

        throw new IllegalArgumentException("Webhook payload must specify orderId, orderNumber, or transactionReference");
    }

    private PaymentStatus resolvePaymentStatus(Order order) {
        if (order == null) {
            return null;
        }
        List<PaymentTransaction> txns = paymentTransactionRepository.findByOrderId(order.getId());
        if (txns.isEmpty()) {
            return null;
        }
        return txns.get(txns.size() - 1).getStatus();
    }

    private void handleLatePaymentRefund(Order order) {
        List<PaymentTransaction> transactions = paymentTransactionRepository.findByOrderId(order.getId());
        for (PaymentTransaction txn : transactions) {
            txn.setStatus(PaymentStatus.LATE_PAYMENT_REFUND_PENDING);
            paymentTransactionRepository.save(txn);

            RefundRequest refundRequest = RefundRequest.builder()
                    .transactionReference(txn.getTransactionReference())
                    .amount(txn.getAmount())
                    .currency(txn.getCurrency())
                    .reason("Late payment received after order expiration")
                    .build();

            try {
                RefundResult refundResult = paymentGateway.initiateRefund(refundRequest);
                log.info("Initiated refund for late payment on order {}: txnRef={}, refundRef={}, status={}",
                        order.getOrderNumber(), txn.getTransactionReference(), refundResult.getRefundReference(), refundResult.getStatus());
            } catch (Exception e) {
                log.error("Failed to initiate refund for late payment on order {} (txnRef={}): {}",
                        order.getOrderNumber(), txn.getTransactionReference(), e.getMessage(), e);
            }
        }
    }
}
