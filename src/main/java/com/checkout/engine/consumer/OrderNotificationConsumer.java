package com.checkout.engine.consumer;

import com.checkout.engine.dto.event.OrderEventPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderNotificationConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${checkout.kafka.topics.order-events:order-events}",
            groupId = "${spring.kafka.consumer.group-id:checkout-engine-group}"
    )
    public void consumeOrderEvent(String message) {
        log.info("Received message on order-events topic: {}", message);
        try {
            OrderEventPayload payload = objectMapper.readValue(message, OrderEventPayload.class);
            String eventType = payload.getEventType();

            if ("OrderCreated".equalsIgnoreCase(eventType)) {
                log.info("ORDER CREATED NOTIFICATION: Order '{}' (ID: {}) created for User {}. Total amount: ${}, status: {}",
                        payload.getOrderNumber(), payload.getOrderId(), payload.getUserId(), payload.getTotalAmount(), payload.getStatus());
            } else if ("OrderConfirmed".equalsIgnoreCase(eventType)) {
                log.info("ORDER CONFIRMATION NOTIFICATION: Order '{}' (ID: {}) for User {} confirmed successfully. Total amount: ${}",
                        payload.getOrderNumber(), payload.getOrderId(), payload.getUserId(), payload.getTotalAmount());
            } else if ("OrderFailed".equalsIgnoreCase(eventType)) {
                log.warn("ORDER FAILURE NOTIFICATION: Order '{}' (ID: {}) for User {} has FAILED.",
                        payload.getOrderNumber(), payload.getOrderId(), payload.getUserId());
            } else if ("OrderExpired".equalsIgnoreCase(eventType)) {
                log.warn("ORDER EXPIRY NOTIFICATION: Order '{}' (ID: {}) for User {} has EXPIRED due to reservation timeout.",
                        payload.getOrderNumber(), payload.getOrderId(), payload.getUserId());
            } else {
                log.info("ORDER NOTIFICATION: Order '{}' received event '{}' with status '{}'",
                        payload.getOrderNumber(), eventType, payload.getStatus());
            }
        } catch (Exception e) {
            log.error("Failed to process order event message: {}", e.getMessage(), e);
        }
    }
}
