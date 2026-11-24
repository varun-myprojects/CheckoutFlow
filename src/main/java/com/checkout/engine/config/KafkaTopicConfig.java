package com.checkout.engine.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${checkout.kafka.topics.order-events:order-events}")
    private String orderEventsTopic;

    @Value("${checkout.kafka.topics.catalog-events:catalog-events}")
    private String catalogEventsTopic;

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(orderEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic catalogEventsTopic() {
        return TopicBuilder.name(catalogEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
