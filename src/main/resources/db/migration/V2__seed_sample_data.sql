-- =========================================================================
-- Checkout Engine: V2 Seed Sample Data Migration
-- Seeds 2 users and 10 products with initial 1:1 inventory
-- =========================================================================

-- 1. Seed 2 Users
INSERT INTO users (id, email, name, phone, created_at, updated_at) VALUES
(1, 'john.doe@example.com', 'John Doe', '+1-555-0101', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(2, 'jane.smith@example.com', 'Jane Smith', '+1-555-0102', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE email = VALUES(email);

-- 2. Seed 10 Products
INSERT INTO products (id, sku, name, description, price, status, created_at, updated_at) VALUES
(1, 'PROD-MACBOOK-M3', 'Apple MacBook Pro 16" M3', '16-inch Liquid Retina XDR, M3 Max chip, 36GB unified memory, 1TB SSD', 3499.00, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(2, 'PROD-DELL-XPS15', 'Dell XPS 15 OLED', '15.6-inch 3.5K OLED touch display, Intel Core i9, 32GB RAM, 1TB SSD', 2299.50, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(3, 'PROD-SONY-WH1000XM5', 'Sony WH-1000XM5 Wireless Headphones', 'Industry-leading noise canceling wireless headphones with Auto NC Optimizer', 399.99, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(4, 'PROD-APPLE-AIRPODSPRO2', 'Apple AirPods Pro 2nd Gen', 'Active Noise Cancellation, Adaptive Audio, USB-C MagSafe Charging Case', 249.00, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(5, 'PROD-LG-C3-OLED', 'LG 55" C3 Series OLED 4K TV', 'Self-lit OLED pixels, α9 AI Processor Gen6, Dolby Vision, 120Hz refresh rate', 1496.99, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(6, 'PROD-LOGI-MXMASTER3S', 'Logitech MX Master 3S Mouse', 'Performance wireless mouse with 8K DPI tracking and Quiet Clicks', 99.99, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(7, 'PROD-KEYCHRON-Q1PRO', 'Keychron Q1 Pro Mechanical Keyboard', 'Wireless custom mechanical keyboard, 75% layout, hot-swappable switches', 199.00, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(8, 'PROD-KINDLE-PAPERWHITE', 'Amazon Kindle Paperwhite 16GB', '6.8-inch display, adjustable warm light, up to 10 weeks of battery life', 149.99, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(9, 'PROD-SAMSUNG-T7-2TB', 'Samsung T7 Shield 2TB Portable SSD', 'Rugged external SSD, USB 3.2 Gen 2, transfer speeds up to 1050 MB/s', 179.99, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(10, 'PROD-ANKER-737-PB', 'Anker 737 Power Bank (PowerCore 24K)', '24,000mAh 3-port portable charger with 140W max output and smart display', 139.99, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE sku = VALUES(sku);

-- 3. Seed Inventory (1:1 with products, available_qty, reserved_qty = 0, version = 0)
INSERT INTO inventory (id, product_id, available_qty, reserved_qty, version, created_at, updated_at) VALUES
(1, 1, 25, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(2, 2, 30, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(3, 3, 100, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(4, 4, 150, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(5, 5, 15, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(6, 6, 80, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(7, 7, 50, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(8, 8, 120, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(9, 9, 75, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
(10, 10, 90, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE product_id = VALUES(product_id);
