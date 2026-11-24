package com.checkout.engine.consumer;

import com.checkout.engine.dto.event.ProductPriceUpdatedEventPayload;
import com.checkout.engine.entity.SavedItem;
import com.checkout.engine.repository.SavedItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceDropAlertConsumer {

    private final SavedItemRepository savedItemRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${checkout.kafka.topics.catalog-events:catalog-events}",
            groupId = "${spring.kafka.consumer.group-id:checkout-engine-group}"
    )
    @Transactional(readOnly = true)
    public void consumeCatalogEvent(String message) {
        log.info("Received message on catalog-events topic: {}", message);
        try {
            ProductPriceUpdatedEventPayload payload = objectMapper.readValue(message, ProductPriceUpdatedEventPayload.class);

            if (!"ProductPriceUpdated".equalsIgnoreCase(payload.getEventType())) {
                log.debug("Skipping unhandled catalog event type: {}", payload.getEventType());
                return;
            }

            Long productId = payload.getProductId();
            BigDecimal newPrice = payload.getNewPrice();
            String sku = payload.getSku();

            // Query saved_items where price_at_save > new_price
            List<SavedItem> eligibleSavedItems = savedItemRepository.findByProductIdAndPriceAtSaveGreaterThan(productId, newPrice);

            if (eligibleSavedItems.isEmpty()) {
                log.info("No saved items found with price_at_save > ${} for product {} ({})", newPrice, sku, productId);
                return;
            }

            log.info("Found {} user(s) eligible for price-drop alerts on SKU '{}'", eligibleSavedItems.size(), sku);

            for (SavedItem savedItem : eligibleSavedItems) {
                BigDecimal savedPrice = savedItem.getPriceAtSave();
                BigDecimal dropAmount = savedPrice.subtract(newPrice);
                String userName = savedItem.getUser() != null ? savedItem.getUser().getName() : "Customer";
                Long userId = savedItem.getUser() != null ? savedItem.getUser().getId() : null;

                log.info("PRICE DROP ALERT: Hi {} (User {}), product '{}' (SKU: {}) you saved at ${} dropped by ${} to ${}!",
                        userName, userId, savedItem.getProduct().getName(), sku, savedPrice, dropAmount, newPrice);
            }
        } catch (Exception e) {
            log.error("Failed to process catalog event message: {}", e.getMessage(), e);
        }
    }
}
