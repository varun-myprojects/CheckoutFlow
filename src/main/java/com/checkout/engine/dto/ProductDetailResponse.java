package com.checkout.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductDetailResponse {

    private Long id;
    private String sku;
    private String name;
    private String description;
    private BigDecimal price;
    private String status;
    private Integer availableQty;
    private Integer reservedQty;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
}
