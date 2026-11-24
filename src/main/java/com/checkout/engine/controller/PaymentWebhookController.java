package com.checkout.engine.controller;

import com.checkout.engine.dto.ApiResponse;
import com.checkout.engine.dto.PaymentWebhookResult;
import com.checkout.engine.service.PaymentWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    @PostMapping("/payment")
    public ResponseEntity<ApiResponse<PaymentWebhookResult>> handlePaymentWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String webhookSignature,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String razorpaySignature
    ) {
        String effectiveSignature = webhookSignature != null ? webhookSignature
                : (signature != null ? signature : razorpaySignature);

        log.info("Received payment webhook callback");
        PaymentWebhookResult result = paymentWebhookService.processWebhook(rawPayload, effectiveSignature);

        return ResponseEntity.ok(ApiResponse.success(result, "Webhook processed successfully"));
    }
}
