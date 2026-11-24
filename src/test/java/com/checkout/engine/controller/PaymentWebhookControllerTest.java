package com.checkout.engine.controller;

import com.checkout.engine.dto.PaymentWebhookResult;
import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.entity.PaymentStatus;
import com.checkout.engine.exception.GlobalExceptionHandler;
import com.checkout.engine.exception.InvalidWebhookSignatureException;
import com.checkout.engine.service.PaymentWebhookService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PaymentWebhookService paymentWebhookService;

    @InjectMocks
    private PaymentWebhookController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void handlePaymentWebhook_validSignature_returns200() throws Exception {
        PaymentWebhookResult mockResult = PaymentWebhookResult.builder()
                .providerEventId("evt_12345")
                .status("SUCCESS")
                .message("Webhook processed successfully")
                .alreadyProcessed(false)
                .orderStatus(OrderStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.CAPTURED)
                .build();

        when(paymentWebhookService.processWebhook(anyString(), anyString())).thenReturn(mockResult);

        String payload = "{\"providerEventId\":\"evt_12345\",\"orderId\":1,\"status\":\"SUCCESS\"}";

        mockMvc.perform(post("/api/v1/webhooks/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", "valid_mock_signature")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.providerEventId").value("evt_12345"))
                .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("CAPTURED"));
    }

    @Test
    void handlePaymentWebhook_invalidSignature_returns400() throws Exception {
        when(paymentWebhookService.processWebhook(anyString(), any()))
                .thenThrow(new InvalidWebhookSignatureException("Invalid webhook signature"));

        mockMvc.perform(post("/api/v1/webhooks/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", "invalid_sig")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid webhook signature"));
    }

    @Test
    void handlePaymentWebhook_duplicateEvent_returns200() throws Exception {
        PaymentWebhookResult duplicateResult = PaymentWebhookResult.builder()
                .providerEventId("evt_dup_999")
                .status("SUCCESS")
                .message("Event already processed")
                .alreadyProcessed(true)
                .orderStatus(OrderStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.CAPTURED)
                .build();

        when(paymentWebhookService.processWebhook(anyString(), anyString())).thenReturn(duplicateResult);

        String payload = "{\"providerEventId\":\"evt_dup_999\",\"orderId\":1,\"status\":\"SUCCESS\"}";

        mockMvc.perform(post("/api/v1/webhooks/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", "valid_mock_signature")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.alreadyProcessed").value(true))
                .andExpect(jsonPath("$.data.providerEventId").value("evt_dup_999"));
    }
}
