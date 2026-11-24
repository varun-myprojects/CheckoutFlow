package com.checkout.engine.controller;

import com.checkout.engine.dto.ApiResponse;
import com.checkout.engine.dto.MockWebhookSimulationRequest;
import com.checkout.engine.dto.MockWebhookSimulationResponse;
import com.checkout.engine.service.payment.MockPaymentGatewayAdapter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mock-gateway")
@RequiredArgsConstructor
public class MockGatewayController {

    private final MockPaymentGatewayAdapter mockPaymentGatewayAdapter;

    @PostMapping("/simulate-webhook")
    public ResponseEntity<ApiResponse<MockWebhookSimulationResponse>> simulateWebhook(
            @Valid @RequestBody MockWebhookSimulationRequest request
    ) {
        MockWebhookSimulationResponse response = mockPaymentGatewayAdapter.simulateWebhook(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Webhook simulated successfully"));
    }
}
