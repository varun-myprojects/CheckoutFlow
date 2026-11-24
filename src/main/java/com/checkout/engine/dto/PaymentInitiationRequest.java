package com.checkout.engine.dto;

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
public class PaymentInitiationRequest {

    private Long orderId;
    private String orderNumber;
    private BigDecimal amount;
    private String currency;
    private String idempotencyKey;
    private String customerEmail;
    private String customerName;
}
