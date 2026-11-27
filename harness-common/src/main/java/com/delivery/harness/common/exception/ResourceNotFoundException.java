package com.delivery.harness.common.exception;

public class ResourceNotFoundException extends HarnessException {

    public ResourceNotFoundException(String message) {
        super(404, message);
    }
}
