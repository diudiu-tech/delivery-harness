package com.delivery.harness.common.exception;

import lombok.Getter;

@Getter
public class HarnessException extends RuntimeException {

    private final int code;

    public HarnessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public HarnessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
