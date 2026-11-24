package com.checkout.engine.controller;

import com.checkout.engine.dto.ApiResponse;
import com.checkout.engine.dto.MockWebhookSimulationRequest;
import com.checkout.engine.dto.MockWebhookSimulationResponse;
import com.checkout.engine.service.payment.MockPaymentGatewayAdapter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Mock Gateway", description = "Mock payment gateway simulator for end-to-end integration testing and fault injection")
@RestController
@RequestMapping("/api/v1/mock-gateway")
@RequiredArgsConstructor
public class MockGatewayController {

    private final MockPaymentGatewayAdapter mockPaymentGatewayAdapter;

    @Operation(summary = "Simulate payment webhook dispatch", description = "Simulates payment gateway webhook events (SUCCESS/FAILURE) with signed HMAC-SHA256 headers, configurable latency, and replay loop")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Webhook simulated successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found")
    })
    @PostMapping("/simulate-webhook")
    public ResponseEntity<ApiResponse<MockWebhookSimulationResponse>> simulateWebhook(
            @Valid @RequestBody MockWebhookSimulationRequest request
    ) {
        MockWebhookSimulationResponse response = mockPaymentGatewayAdapter.simulateWebhook(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Webhook simulated successfully"));
    }
}
