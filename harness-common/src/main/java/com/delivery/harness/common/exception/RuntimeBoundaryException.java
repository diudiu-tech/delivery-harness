package com.delivery.harness.common.exception;

/** A safe, typed error from the trusted runtime boundary, independent of platform persistence. */
public final class RuntimeBoundaryException extends HarnessException {
    private final String reasonCode;

    public RuntimeBoundaryException(int status, String reasonCode, String message) {
        super(status, message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
