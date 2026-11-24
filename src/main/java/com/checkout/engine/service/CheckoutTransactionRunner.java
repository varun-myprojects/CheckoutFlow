package com.checkout.engine.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

@Component
public class CheckoutTransactionRunner {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T runInTransaction(Supplier<T> action) {
        return action.get();
    }
}
