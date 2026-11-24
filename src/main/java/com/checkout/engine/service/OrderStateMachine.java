package com.checkout.engine.service;

import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.exception.InvalidStateTransitionException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Component
public class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS;

    static {
        Map<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);

        // CREATED -> PENDING_PAYMENT, CANCELLED
        transitions.put(OrderStatus.CREATED, Set.of(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED));

        // PENDING_PAYMENT -> CONFIRMED, FAILED, EXPIRED, CANCELLED
        transitions.put(OrderStatus.PENDING_PAYMENT, Set.of(
                OrderStatus.CONFIRMED,
                OrderStatus.FAILED,
                OrderStatus.EXPIRED,
                OrderStatus.CANCELLED
        ));

        // CONFIRMED -> CANCELLED
        transitions.put(OrderStatus.CONFIRMED, Set.of(OrderStatus.CANCELLED));

        // Terminal states
        transitions.put(OrderStatus.FAILED, Collections.emptySet());
        transitions.put(OrderStatus.EXPIRED, Collections.emptySet());
        transitions.put(OrderStatus.CANCELLED, Collections.emptySet());

        VALID_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    public void validateTransition(OrderStatus current, OrderStatus target) {
        if (current == null || target == null) {
            throw new InvalidStateTransitionException("Order status cannot be null");
        }

        if (current == target) {
            return;
        }

        Set<OrderStatus> allowed = VALID_TRANSITIONS.getOrDefault(current, Collections.emptySet());
        if (!allowed.contains(target)) {
            throw new InvalidStateTransitionException("Order", current.name(), target.name());
        }
    }

    public boolean canTransition(OrderStatus current, OrderStatus target) {
        if (current == null || target == null) {
            return false;
        }
        if (current == target) {
            return true;
        }
        return VALID_TRANSITIONS.getOrDefault(current, Collections.emptySet()).contains(target);
    }
}
