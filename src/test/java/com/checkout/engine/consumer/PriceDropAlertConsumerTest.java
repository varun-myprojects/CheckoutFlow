package com.checkout.engine.consumer;

import com.checkout.engine.entity.Product;
import com.checkout.engine.entity.SavedItem;
import com.checkout.engine.entity.User;
import com.checkout.engine.repository.SavedItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceDropAlertConsumerTest {

    @Mock
    private SavedItemRepository savedItemRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private PriceDropAlertConsumer consumer;

    @Test
    void consumeCatalogEvent_productPriceUpdated_alertsEligibleUsers() {
        String message = """
                {
                    "productId": 6,
                    "sku": "PROD-LOGI-MXMASTER3S",
                    "name": "Logitech MX Master 3S Mouse",
                    "oldPrice": 99.99,
                    "newPrice": 79.99,
                    "eventType": "ProductPriceUpdated"
                }
                """;

        User user = User.builder().id(1L).name("Varun").email("varun@example.com").build();
        Product product = Product.builder().id(6L).sku("PROD-LOGI-MXMASTER3S").name("Logitech MX Master 3S Mouse").build();
        SavedItem savedItem = SavedItem.builder()
                .id(10L)
                .user(user)
                .product(product)
                .priceAtSave(BigDecimal.valueOf(99.99))
                .build();

        when(savedItemRepository.findByProductIdAndPriceAtSaveGreaterThan(eq(6L), eq(BigDecimal.valueOf(79.99))))
                .thenReturn(List.of(savedItem));

        assertDoesNotThrow(() -> consumer.consumeCatalogEvent(message));

        verify(savedItemRepository).findByProductIdAndPriceAtSaveGreaterThan(eq(6L), eq(BigDecimal.valueOf(79.99)));
    }

    @Test
    void consumeCatalogEvent_noSavedItemsEligible_noAlerts() {
        String message = """
                {
                    "productId": 6,
                    "sku": "PROD-LOGI-MXMASTER3S",
                    "name": "Logitech MX Master 3S Mouse",
                    "oldPrice": 99.99,
                    "newPrice": 89.99,
                    "eventType": "ProductPriceUpdated"
                }
                """;

        when(savedItemRepository.findByProductIdAndPriceAtSaveGreaterThan(eq(6L), eq(BigDecimal.valueOf(89.99))))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> consumer.consumeCatalogEvent(message));

        verify(savedItemRepository).findByProductIdAndPriceAtSaveGreaterThan(eq(6L), eq(BigDecimal.valueOf(89.99)));
    }

    @Test
    void consumeCatalogEvent_unhandledEventType_ignored() {
        String message = """
                {
                    "productId": 6,
                    "eventType": "ProductCreated"
                }
                """;

        assertDoesNotThrow(() -> consumer.consumeCatalogEvent(message));

        verify(savedItemRepository, never()).findByProductIdAndPriceAtSaveGreaterThan(any(), any());
    }

    @Test
    void consumeCatalogEvent_malformedJson_handledGracefully() {
        assertDoesNotThrow(() -> consumer.consumeCatalogEvent("{ broken json }"));
        verify(savedItemRepository, never()).findByProductIdAndPriceAtSaveGreaterThan(any(), any());
    }
}
