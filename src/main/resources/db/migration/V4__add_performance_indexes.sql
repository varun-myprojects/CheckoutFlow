-- =========================================================================
-- Checkout Engine: V4 Performance Indexes Migration
-- =========================================================================

-- 1. Composite index on saved_items (user_id, created_at) for user's save-for-later history/display
CREATE INDEX idx_saved_items_user_created ON saved_items (user_id, created_at);

-- 2. Composite index on orders (user_id, created_at) for user order history queries
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at);

-- 3. Composite index on inventory_reservations (status, expires_at) for background sweeper queries
CREATE INDEX idx_reservations_status_expires ON inventory_reservations (status, expires_at);

-- 4. Composite index on cart_items (cart_id, product_id) for cart lookup and item deduplication
CREATE INDEX idx_cart_items_cart_product ON cart_items (cart_id, product_id);
