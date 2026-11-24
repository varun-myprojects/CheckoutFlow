package com.checkout.engine.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductPriceUpdatedEventPayload {
    private Long productId;
    private String sku;
    private String name;
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    private String eventType;
    private Instant timestamp;
}
