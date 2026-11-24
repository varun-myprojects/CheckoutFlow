package com.checkout.engine.controller;

import com.checkout.engine.dto.ApiResponse;
import com.checkout.engine.dto.PaymentWebhookResult;
import com.checkout.engine.service.PaymentWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payment Webhook", description = "HMAC-SHA256 verified payment callback endpoint with table-enforced idempotency and transactional stock consumption/release")
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    @Operation(
            summary = "Process Payment Gateway Webhook",
            description = "Validates HMAC-SHA256 signature, checks idempotency via unique provider_event_id, confirms order + consumes stock on PAYMENT_SUCCESS, or releases stock on PAYMENT_FAILED / handles late payments."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Webhook verified and processed successfully (or idempotent duplicate)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid HMAC-SHA256 signature"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Malformed JSON payload or missing provider_event_id")
    })
    @PostMapping("/payment")
    public ResponseEntity<ApiResponse<PaymentWebhookResult>> handlePaymentWebhook(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Raw JSON webhook payload dispatched by payment gateway",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    name = "PaymentSuccessExample",
                                    value = "{\"providerEventId\":\"evt-pay-success-001\",\"eventType\":\"PAYMENT_SUCCESS\",\"orderId\":1,\"transactionReference\":\"mock_txn_9a8b7c6d5e\"}"
                            )
                    )
            )
            @RequestBody String rawPayload,

            @Parameter(
                    name = "X-Webhook-Signature",
                    in = ParameterIn.HEADER,
                    required = false,
                    description = "HMAC-SHA256 hex signature computed over raw request body using shared webhook secret",
                    example = "b7a2d48c894f2913e61be6b1b7454d6537f763f0"
            )
            @RequestHeader(value = "X-Webhook-Signature", required = false) String webhookSignature,

            @Parameter(
                    name = "X-Signature",
                    in = ParameterIn.HEADER,
                    required = false,
                    description = "Alternative HMAC-SHA256 signature header"
            )
            @RequestHeader(value = "X-Signature", required = false) String signature,

            @Parameter(
                    name = "X-Razorpay-Signature",
                    in = ParameterIn.HEADER,
                    required = false,
                    description = "Razorpay HMAC-SHA256 signature header"
            )
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String razorpaySignature
    ) {
        String effectiveSignature = webhookSignature != null ? webhookSignature
                : (signature != null ? signature : razorpaySignature);

        log.info("Received payment webhook callback");
        PaymentWebhookResult result = paymentWebhookService.processWebhook(rawPayload, effectiveSignature);

        return ResponseEntity.ok(ApiResponse.success(result, "Webhook processed successfully"));
    }
}
