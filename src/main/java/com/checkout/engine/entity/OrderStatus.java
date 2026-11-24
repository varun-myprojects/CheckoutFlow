package com.checkout.engine.entity;

public enum OrderStatus {
    CREATED,
    PENDING_PAYMENT,
    CONFIRMED,
    FAILED,
    EXPIRED,
    CANCELLED
}
