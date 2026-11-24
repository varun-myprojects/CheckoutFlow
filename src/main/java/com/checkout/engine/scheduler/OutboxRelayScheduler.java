package com.checkout.engine.scheduler;

import com.checkout.engine.entity.OutboxEvent;
import com.checkout.engine.entity.OutboxStatus;
import com.checkout.engine.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    public static final String TOPIC_ORDER_EVENTS = "order-events";
    public static final String TOPIC_CATALOG_EVENTS = "catalog-events";

    private final OutboxEventRepository outboxEventRepository;

    @Autowired(required = false)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${checkout.outbox.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${checkout.outbox.relay.fixed-delay-ms:2000}")
    @Transactional
    public void relayOutboxEvents() {
        if (kafkaTemplate == null) {
            log.trace("KafkaTemplate not available; skipping outbox relay");
            return;
        }

        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEventsWithSkipLocked(batchSize);
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Found {} pending outbox events to relay", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            String topic = resolveTopic(event);
            try {
                // Send with aggregateId as message key for partition ordering
                kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                        .get(5, TimeUnit.SECONDS);

                event.setStatus(OutboxStatus.PUBLISHED);
                outboxEventRepository.save(event);

                log.info("Successfully published outbox event [id={}, type={}, aggregateId={}] to topic {}",
                        event.getId(), event.getEventType(), event.getAggregateId(), topic);
            } catch (Exception e) {
                log.error("Failed to relay outbox event [id={}, type={}] to topic {}: {}",
                        event.getId(), event.getEventType(), topic, e.getMessage());
                // Event remains PENDING to retry on next tick
            }
        }
    }

    public String resolveTopic(OutboxEvent event) {
        String eventType = event.getEventType();
        String aggregateType = event.getAggregateType();

        if ("ProductPriceUpdated".equalsIgnoreCase(eventType)
                || "PRODUCT".equalsIgnoreCase(aggregateType)
                || "CATALOG".equalsIgnoreCase(aggregateType)) {
            return TOPIC_CATALOG_EVENTS;
        }

        return TOPIC_ORDER_EVENTS;
    }
}
