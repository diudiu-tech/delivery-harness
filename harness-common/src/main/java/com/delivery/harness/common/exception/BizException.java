package com.delivery.harness.common.exception;

public class BizException extends HarnessException {

    public BizException(String message) {
        super(1000, message);
    }

    public BizException(int code, String message) {
        super(code, message);
    }
}
