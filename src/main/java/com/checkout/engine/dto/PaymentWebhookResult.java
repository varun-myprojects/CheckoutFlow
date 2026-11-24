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
public class PaymentWebhookResult {
    private String providerEventId;
    private String status;
    private String message;
    private boolean alreadyProcessed;
    private OrderStatus orderStatus;
    private PaymentStatus paymentStatus;
}
