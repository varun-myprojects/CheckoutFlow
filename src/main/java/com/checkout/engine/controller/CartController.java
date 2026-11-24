package com.checkout.engine.controller;

import com.checkout.engine.dto.AddToCartRequest;
import com.checkout.engine.dto.ApiResponse;
import com.checkout.engine.dto.CartResponse;
import com.checkout.engine.dto.UpdateCartItemRequest;
import com.checkout.engine.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Cart", description = "Shopping cart operations with Redis cache-aside and 2N+1 query elimination")
@RestController
@RequestMapping("/api/v1/carts")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Operation(summary = "Get user's active cart", description = "Retrieves active cart for a user with enriched product and stock data (cache-aside via cart:{userId})")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cart retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @Parameter(description = "ID of the user", example = "1") @PathVariable Long userId) {
        CartResponse cart = cartService.getCart(userId);
        return ResponseEntity.ok(ApiResponse.success(cart));
    }

    @Operation(summary = "Add item to cart", description = "Adds a product to the user's active cart or increments quantity, evicting cart:{userId}")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Item added to cart successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Insufficient stock or invalid input"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User or product not found")
    })
    @PostMapping("/{userId}/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItemToCart(
            @Parameter(description = "ID of the user", example = "1") @PathVariable Long userId,
            @Valid @RequestBody AddToCartRequest request
    ) {
        CartResponse cart = cartService.addItemToCart(userId, request);
        return ResponseEntity.ok(ApiResponse.success(cart, "Item added to cart successfully"));
    }

    @Operation(summary = "Update cart item quantity", description = "Updates item quantity in cart, evicting cart:{userId}")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cart item updated successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Insufficient stock or invalid quantity"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Cart item not found")
    })
    @PatchMapping("/{userId}/items/{productId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateItemQuantity(
            @Parameter(description = "ID of the user", example = "1") @PathVariable Long userId,
            @Parameter(description = "ID of the product", example = "6") @PathVariable Long productId,
            @Valid @RequestBody UpdateCartItemRequest request
    ) {
        CartResponse cart = cartService.updateItemQuantity(userId, productId, request);
        return ResponseEntity.ok(ApiResponse.success(cart, "Cart item updated successfully"));
    }

    @Operation(summary = "Remove item from cart", description = "Removes a product from the user's active cart, evicting cart:{userId}")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cart item removed successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Cart item not found")
    })
    @DeleteMapping("/{userId}/items/{productId}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItemFromCart(
            @Parameter(description = "ID of the user", example = "1") @PathVariable Long userId,
            @Parameter(description = "ID of the product", example = "6") @PathVariable Long productId
    ) {
        CartResponse cart = cartService.removeItemFromCart(userId, productId);
        return ResponseEntity.ok(ApiResponse.success(cart, "Cart item removed successfully"));
    }
}
