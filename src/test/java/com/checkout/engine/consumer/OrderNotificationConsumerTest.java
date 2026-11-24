package com.checkout.engine.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ExtendWith(MockitoExtension.class)
class OrderNotificationConsumerTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private OrderNotificationConsumer consumer;

    @Test
    void consumeOrderEvent_orderConfirmed_processesCleanly() {
        String message = """
                {
                    "orderId": 42,
                    "orderNumber": "ORD-4242",
                    "userId": 1,
                    "totalAmount": 199.99,
                    "eventType": "OrderConfirmed",
                    "status": "CONFIRMED"
                }
                """;

        assertDoesNotThrow(() -> consumer.consumeOrderEvent(message));
    }

    @Test
    void consumeOrderEvent_orderExpired_processesCleanly() {
        String message = """
                {
                    "orderId": 43,
                    "orderNumber": "ORD-4343",
                    "userId": 2,
                    "eventType": "OrderExpired",
                    "status": "EXPIRED"
                }
                """;

        assertDoesNotThrow(() -> consumer.consumeOrderEvent(message));
    }

    @Test
    void consumeOrderEvent_orderCreated_processesCleanly() {
        String message = """
                {
                    "orderId": 41,
                    "orderNumber": "ORD-4141",
                    "userId": 1,
                    "totalAmount": 99.99,
                    "eventType": "OrderCreated",
                    "status": "PENDING_PAYMENT"
                }
                """;

        assertDoesNotThrow(() -> consumer.consumeOrderEvent(message));
    }

    @Test
    void consumeOrderEvent_orderFailed_processesCleanly() {
        String message = """
                {
                    "orderId": 44,
                    "orderNumber": "ORD-4444",
                    "userId": 1,
                    "eventType": "OrderFailed",
                    "status": "FAILED"
                }
                """;

        assertDoesNotThrow(() -> consumer.consumeOrderEvent(message));
    }

    @Test
    void consumeOrderEvent_malformedJson_handlesGracefully() {
        String malformed = "{ invalid json }";
        assertDoesNotThrow(() -> consumer.consumeOrderEvent(malformed));
    }
}
