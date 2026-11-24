package com.checkout.engine.controller;

import com.checkout.engine.dto.MockWebhookSimulationRequest;
import com.checkout.engine.dto.MockWebhookSimulationResponse;
import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.entity.PaymentStatus;
import com.checkout.engine.exception.GlobalExceptionHandler;
import com.checkout.engine.service.payment.MockPaymentGatewayAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MockGatewayControllerTest {

    private MockMvc mockMvc;

    @Mock
    private MockPaymentGatewayAdapter mockPaymentGatewayAdapter;

    @InjectMocks
    private MockGatewayController controller;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void simulateWebhook_success_returns200() throws Exception {
        MockWebhookSimulationResponse mockResponse = MockWebhookSimulationResponse.builder()
                .transactionReference("mock_txn_12345")
                .orderId(1L)
                .orderNumber("ORD-1")
                .paymentStatus(PaymentStatus.CAPTURED)
                .orderStatus(OrderStatus.CONFIRMED)
                .webhookEvent("payment.captured")
                .signature("mock_sig")
                .processedCount(1)
                .message("Mock webhook simulated successfully")
                .build();

        when(mockPaymentGatewayAdapter.simulateWebhook(any(MockWebhookSimulationRequest.class)))
                .thenReturn(mockResponse);

        MockWebhookSimulationRequest request = MockWebhookSimulationRequest.builder()
                .transactionReference("mock_txn_12345")
                .status("SUCCESS")
                .latencyMs(0L)
                .replayCount(1)
                .build();

        mockMvc.perform(post("/api/v1/mock-gateway/simulate-webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionReference").value("mock_txn_12345"))
                .andExpect(jsonPath("$.data.paymentStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));
    }

    @Test
    void simulateWebhook_blankTransactionReference_returns400() throws Exception {
        MockWebhookSimulationRequest request = MockWebhookSimulationRequest.builder()
                .transactionReference("")
                .status("SUCCESS")
                .build();

        mockMvc.perform(post("/api/v1/mock-gateway/simulate-webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
