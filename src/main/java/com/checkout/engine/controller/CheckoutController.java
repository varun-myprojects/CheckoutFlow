package com.checkout.engine.controller;

import com.checkout.engine.dto.ApiResponse;
import com.checkout.engine.dto.CheckoutResponse;
import com.checkout.engine.service.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Checkout", description = "Concurrent checkout orchestration, distributed/pessimistic locking, order state machine, and transactional outbox events")
@RestController
@RequestMapping("/api/v1/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;

    @Operation(
            summary = "Execute Checkout",
            description = "Performs transactional checkout: acquires deadlock-free sorted locks, snapshots prices, reserves stock, transitions cart to ORDERED, initiates payment, evicts cache, and records an OrderCreated outbox event."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Checkout executed successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Idempotent replay of previously completed checkout"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Active cart empty, insufficient inventory, or invalid state transition"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User or cart not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Idempotency key reused with mismatched user")
    })
    @PostMapping("/{userId}")
    public ResponseEntity<ApiResponse<CheckoutResponse>> checkout(
            @Parameter(description = "ID of the user checking out", example = "1")
            @PathVariable Long userId,

            @Parameter(
                    name = "Idempotency-Key",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "Unique client-generated idempotency key to prevent duplicate checkouts",
                    example = "chk-prod-user1-001"
            )
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey
    ) {
        CheckoutResponse response = checkoutService.checkout(userId, idempotencyKey);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Checkout successful"));
    }
}
