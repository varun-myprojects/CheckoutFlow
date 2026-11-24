package com.checkout.engine.dto;

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
public class PaymentInitiationResult {

    private String transactionReference;
    private String paymentUrl;
    private PaymentStatus status;
    private String gatewayName;
}
