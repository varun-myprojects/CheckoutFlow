package com.checkout.engine.service;

import com.checkout.engine.entity.OrderStatus;
import com.checkout.engine.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineTest {

    private OrderStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new OrderStateMachine();
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, PENDING_PAYMENT",
            "CREATED, CANCELLED",
            "PENDING_PAYMENT, CONFIRMED",
            "PENDING_PAYMENT, FAILED",
            "PENDING_PAYMENT, EXPIRED",
            "PENDING_PAYMENT, CANCELLED",
            "CONFIRMED, CANCELLED"
    })
    void validateTransition_validTransitions_success(OrderStatus current, OrderStatus target) {
        assertThatCode(() -> stateMachine.validateTransition(current, target))
                .doesNotThrowAnyException();
        assertThat(stateMachine.canTransition(current, target)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "EXPIRED, CONFIRMED",
            "EXPIRED, PENDING_PAYMENT",
            "FAILED, CONFIRMED",
            "FAILED, PENDING_PAYMENT",
            "CANCELLED, CONFIRMED",
            "CANCELLED, PENDING_PAYMENT",
            "CONFIRMED, PENDING_PAYMENT",
            "CONFIRMED, EXPIRED",
            "CREATED, CONFIRMED",
            "CREATED, EXPIRED"
    })
    void validateTransition_illegalTransitions_throwsException(OrderStatus current, OrderStatus target) {
        assertThatThrownBy(() -> stateMachine.validateTransition(current, target))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining(String.format("Invalid state transition for Order from '%s' to '%s'", current, target));
        assertThat(stateMachine.canTransition(current, target)).isFalse();
    }

    @Test
    void validateTransition_sameStatus_isNoOp() {
        assertThatCode(() -> stateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.CREATED))
                .doesNotThrowAnyException();
        assertThat(stateMachine.canTransition(OrderStatus.CREATED, OrderStatus.CREATED)).isTrue();
    }

    @Test
    void validateTransition_nullStatus_throwsException() {
        assertThatThrownBy(() -> stateMachine.validateTransition(null, OrderStatus.CONFIRMED))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
