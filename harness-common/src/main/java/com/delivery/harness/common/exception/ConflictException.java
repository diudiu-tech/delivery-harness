package com.delivery.harness.common.exception;

public class ConflictException extends HarnessException {

    public ConflictException(String message) {
        super(409, message);
    }
}
