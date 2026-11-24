package com.checkout.engine.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentWebhookPayload {

    private String providerEventId;
    private String eventId;
    private String id;

    private String event;
    private String eventType;

    private String transactionReference;
    private String paymentId;

    private String orderNumber;
    private Long orderId;

    private String status;
    private String outcome;

    private BigDecimal amount;
    private String currency;

    public String getEffectiveProviderEventId() {
        if (providerEventId != null && !providerEventId.isBlank()) {
            return providerEventId.trim();
        }
        if (eventId != null && !eventId.isBlank()) {
            return eventId.trim();
        }
        if (id != null && !id.isBlank()) {
            return id.trim();
        }
        return null;
    }

    public boolean isSuccess() {
        if ("payment.captured".equalsIgnoreCase(event)
                || "PAYMENT_SUCCESS".equalsIgnoreCase(event)
                || "PAYMENT_SUCCESS".equalsIgnoreCase(eventType)
                || "payment.captured".equalsIgnoreCase(eventType)) {
            return true;
        }
        if ("SUCCESS".equalsIgnoreCase(status)
                || "CAPTURED".equalsIgnoreCase(status)
                || "SUCCESS".equalsIgnoreCase(outcome)) {
            return true;
        }
        return false;
    }

    public boolean isFailure() {
        if ("payment.failed".equalsIgnoreCase(event)
                || "PAYMENT_FAILED".equalsIgnoreCase(event)
                || "PAYMENT_FAILED".equalsIgnoreCase(eventType)
                || "payment.failed".equalsIgnoreCase(eventType)) {
            return true;
        }
        if ("FAILURE".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "FAILURE".equalsIgnoreCase(outcome)) {
            return true;
        }
        return false;
    }
}
