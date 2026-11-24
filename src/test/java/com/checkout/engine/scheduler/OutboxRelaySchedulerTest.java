package com.checkout.engine.scheduler;

import com.checkout.engine.entity.OutboxEvent;
import com.checkout.engine.entity.OutboxStatus;
import com.checkout.engine.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelaySchedulerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private OutboxRelayScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 50);
        ReflectionTestUtils.setField(scheduler, "kafkaTemplate", kafkaTemplate);
    }

    @Test
    void relayOutboxEvents_publishesOrderEventsAndMarksPublished() {
        OutboxEvent event = OutboxEvent.builder()
                .id(1L)
                .aggregateType("ORDER")
                .aggregateId("100")
                .eventType("OrderConfirmed")
                .payload("{\"orderId\":100}")
                .status(OutboxStatus.PENDING)
                .build();

        when(outboxEventRepository.findPendingEventsWithSkipLocked(50))
                .thenReturn(List.of(event));

        SendResult<String, String> sendResult = mock(SendResult.class);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(eq("order-events"), eq("100"), eq("{\"orderId\":100}")))
                .thenReturn(future);

        scheduler.relayOutboxEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(outboxEventRepository).save(event);
    }

    @Test
    void relayOutboxEvents_publishesCatalogEventsAndMarksPublished() {
        OutboxEvent event = OutboxEvent.builder()
                .id(2L)
                .aggregateType("PRODUCT")
                .aggregateId("5")
                .eventType("ProductPriceUpdated")
                .payload("{\"productId\":5,\"newPrice\":99.99}")
                .status(OutboxStatus.PENDING)
                .build();

        when(outboxEventRepository.findPendingEventsWithSkipLocked(50))
                .thenReturn(List.of(event));

        SendResult<String, String> sendResult = mock(SendResult.class);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(eq("catalog-events"), eq("5"), eq("{\"productId\":5,\"newPrice\":99.99}")))
                .thenReturn(future);

        scheduler.relayOutboxEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(outboxEventRepository).save(event);
    }

    @Test
    void relayOutboxEvents_kafkaSendFails_leavesEventPendingForRetry() {
        OutboxEvent event = OutboxEvent.builder()
                .id(3L)
                .aggregateType("ORDER")
                .aggregateId("101")
                .eventType("OrderExpired")
                .payload("{\"orderId\":101}")
                .status(OutboxStatus.PENDING)
                .build();

        when(outboxEventRepository.findPendingEventsWithSkipLocked(50))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failedFuture);

        scheduler.relayOutboxEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        verify(outboxEventRepository, never()).save(event);
    }

    @Test
    void relayOutboxEvents_emptyQueue_doesNothing() {
        when(outboxEventRepository.findPendingEventsWithSkipLocked(50))
                .thenReturn(Collections.emptyList());

        scheduler.relayOutboxEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void resolveTopic_routesCorrectly() {
        OutboxEvent orderEvent = OutboxEvent.builder()
                .aggregateType("ORDER")
                .eventType("OrderConfirmed")
                .build();
        assertThat(scheduler.resolveTopic(orderEvent)).isEqualTo("order-events");

        OutboxEvent expiredEvent = OutboxEvent.builder()
                .aggregateType("ORDER")
                .eventType("OrderExpired")
                .build();
        assertThat(scheduler.resolveTopic(expiredEvent)).isEqualTo("order-events");

        OutboxEvent catalogEvent = OutboxEvent.builder()
                .aggregateType("PRODUCT")
                .eventType("ProductPriceUpdated")
                .build();
        assertThat(scheduler.resolveTopic(catalogEvent)).isEqualTo("catalog-events");
    }
}
