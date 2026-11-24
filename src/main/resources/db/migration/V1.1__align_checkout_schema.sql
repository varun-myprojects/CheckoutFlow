-- =========================================================================
-- Checkout Engine: V1.1 Schema Alignment Migration
-- =========================================================================

-- 1. saved_items: Add price_at_save with non-negative check constraint
ALTER TABLE saved_items
    ADD COLUMN price_at_save DECIMAL(12, 2) NOT NULL,
    ADD CONSTRAINT chk_saved_items_price_at_save CHECK (price_at_save >= 0);

-- 2. orders: Add idempotency_key with unique constraint, update default status to 'CREATED'
ALTER TABLE orders
    ADD COLUMN idempotency_key VARCHAR(128) NULL,
    ADD CONSTRAINT uk_orders_idempotency_key UNIQUE (idempotency_key),
    ALTER COLUMN status SET DEFAULT 'CREATED';

-- 3. inventory_reservations: Drop FK, make order_id NOT NULL, re-add FK with RESTRICT,
--    update default status to 'ACTIVE', drop backwards composite index
ALTER TABLE inventory_reservations
    DROP FOREIGN KEY fk_reservations_order;

ALTER TABLE inventory_reservations
    MODIFY COLUMN order_id BIGINT NOT NULL;

ALTER TABLE inventory_reservations
    ADD CONSTRAINT fk_reservations_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT,
    ALTER COLUMN status SET DEFAULT 'ACTIVE',
    DROP INDEX idx_reservations_expires_status;

-- 4. payment_transactions: Update default status to 'INITIATED'
ALTER TABLE payment_transactions
    ALTER COLUMN status SET DEFAULT 'INITIATED';
