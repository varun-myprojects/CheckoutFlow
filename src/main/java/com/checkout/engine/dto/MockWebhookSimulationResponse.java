package com.checkout.engine.dto;

import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.entity.PaymentStatus;
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
public class MockWebhookSimulationResponse {

    private String transactionReference;
    private Long orderId;
    private String orderNumber;
    private PaymentStatus paymentStatus;
    private OrderStatus orderStatus;
    private String webhookEvent;
    private String signature;
    private int processedCount;
    private String message;
}
