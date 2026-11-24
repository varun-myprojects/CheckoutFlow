-- =========================================================================
-- Checkout Engine: V3 Webhook Idempotency Migration
-- =========================================================================

CREATE TABLE processed_webhook_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NULL,
    payload TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PROCESSED',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_webhook_provider_event_id UNIQUE (provider_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
