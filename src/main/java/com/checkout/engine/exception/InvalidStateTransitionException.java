package com.checkout.engine.exception;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String message) {
        super(message);
    }

    public InvalidStateTransitionException(String entityName, String currentState, String targetState) {
        super(String.format("Invalid state transition for %s from '%s' to '%s'", entityName, currentState, targetState));
    }
}
