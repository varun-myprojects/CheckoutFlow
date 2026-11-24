package com.checkout.engine.controller;

import com.checkout.engine.dto.ApiResponse;
import com.checkout.engine.dto.CartResponse;
import com.checkout.engine.dto.SavedItemResponse;
import com.checkout.engine.service.SavedItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class SavedItemController {

    private final SavedItemService savedItemService;

    @PostMapping("/api/v1/carts/{userId}/items/{productId}/save-for-later")
    public ResponseEntity<ApiResponse<SavedItemResponse>> saveForLater(
            @PathVariable Long userId,
            @PathVariable Long productId
    ) {
        SavedItemResponse response = savedItemService.saveForLater(userId, productId);
        return ResponseEntity.ok(ApiResponse.success(response, "Item moved to Saved for Later"));
    }

    @PostMapping("/api/v1/saved-items/{userId}/{productId}/move-to-cart")
    public ResponseEntity<ApiResponse<CartResponse>> moveToCart(
            @PathVariable Long userId,
            @PathVariable Long productId
    ) {
        CartResponse response = savedItemService.moveToCart(userId, productId);
        return ResponseEntity.ok(ApiResponse.success(response, "Item moved to cart"));
    }

    @GetMapping("/api/v1/saved-items/{userId}")
    public ResponseEntity<ApiResponse<List<SavedItemResponse>>> getSavedItems(
            @PathVariable Long userId
    ) {
        List<SavedItemResponse> items = savedItemService.getSavedItems(userId);
        return ResponseEntity.ok(ApiResponse.success(items));
    }
}
