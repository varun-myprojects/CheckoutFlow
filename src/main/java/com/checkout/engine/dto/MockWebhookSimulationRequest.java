package com.checkout.engine.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MockWebhookSimulationRequest {

    private String transactionReference;
    private Long orderId;
    private String orderNumber;

    @Builder.Default
    private String status = "SUCCESS"; // SUCCESS or FAILURE

    private String outcome; // alias for status

    @Builder.Default
    private Long latencyMs = 0L;

    private Long delayMs; // alias for latencyMs

    @Builder.Default
    private Integer replayCount = 1;

    @JsonIgnore
    @AssertTrue(message = "Either transactionReference, orderId, or orderNumber must be provided")
    public boolean isValidIdentifier() {
        return (transactionReference != null && !transactionReference.isBlank())
                || orderId != null
                || (orderNumber != null && !orderNumber.isBlank());
    }

    public String getEffectiveStatus() {
        if (outcome != null && !outcome.isBlank()) {
            return outcome;
        }
        return status != null && !status.isBlank() ? status : "SUCCESS";
    }

    public Long getEffectiveLatencyMs() {
        if (delayMs != null) {
            return delayMs;
        }
        return latencyMs != null ? latencyMs : 0L;
    }
}
