package com.checkout.engine.dto;

import com.checkout.engine.entity.CartStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartResponse {

    private Long cartId;
    private Long userId;
    private CartStatus status;
    private List<CartItemResponse> items;
    private BigDecimal subtotal;
    private Integer totalItems;
    private Instant updatedAt;
}
