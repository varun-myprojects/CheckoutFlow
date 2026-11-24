package com.checkout.engine.repository;

import com.checkout.engine.entity.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProcessedWebhookEventRepository extends JpaRepository<ProcessedWebhookEvent, Long> {
    boolean existsByProviderEventId(String providerEventId);
    Optional<ProcessedWebhookEvent> findByProviderEventId(String providerEventId);
}
