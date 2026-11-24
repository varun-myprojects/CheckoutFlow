package com.checkout.engine.controller;

import com.checkout.engine.dto.ApiResponse;
import com.checkout.engine.dto.CartResponse;
import com.checkout.engine.dto.SavedItemResponse;
import com.checkout.engine.service.SavedItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Save for Later", description = "Save-for-Later item management, real-time price difference calculations, and cross-service cache eviction")
@RestController
@RequiredArgsConstructor
public class SavedItemController {

    private final SavedItemService savedItemService;

    @Operation(summary = "Move item from cart to Save for Later", description = "Atomically removes item from cart, creates/updates saved item with price snapshot, and evicts cart and saved caches")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Item moved to Saved for Later"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Cart item not found")
    })
    @PostMapping("/api/v1/carts/{userId}/items/{productId}/save-for-later")
    public ResponseEntity<ApiResponse<SavedItemResponse>> saveForLater(
            @Parameter(description = "ID of the user", example = "1") @PathVariable Long userId,
            @Parameter(description = "ID of the product", example = "6") @PathVariable Long productId
    ) {
        SavedItemResponse response = savedItemService.saveForLater(userId, productId);
        return ResponseEntity.ok(ApiResponse.success(response, "Item moved to Saved for Later"));
    }

    @Operation(summary = "Move saved item back to cart", description = "Moves item from Save for Later back to cart, validating current inventory stock levels")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Item moved to cart"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Product out of stock"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Saved item not found")
    })
    @PostMapping("/api/v1/saved-items/{userId}/{productId}/move-to-cart")
    public ResponseEntity<ApiResponse<CartResponse>> moveToCart(
            @Parameter(description = "ID of the user", example = "1") @PathVariable Long userId,
            @Parameter(description = "ID of the product", example = "6") @PathVariable Long productId
    ) {
        CartResponse response = savedItemService.moveToCart(userId, productId);
        return ResponseEntity.ok(ApiResponse.success(response, "Item moved to cart"));
    }

    @Operation(summary = "Get user's saved items", description = "Returns saved items enriched with current price, price difference (INCREASED/DECREASED/NO_CHANGE), and stock availability")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Saved items retrieved successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping("/api/v1/saved-items/{userId}")
    public ResponseEntity<ApiResponse<List<SavedItemResponse>>> getSavedItems(
            @Parameter(description = "ID of the user", example = "1") @PathVariable Long userId
    ) {
        List<SavedItemResponse> items = savedItemService.getSavedItems(userId);
        return ResponseEntity.ok(ApiResponse.success(items));
    }
}
