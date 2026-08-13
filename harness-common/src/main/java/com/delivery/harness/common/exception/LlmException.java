package com.delivery.harness.common.exception;

public class LlmException extends HarnessException {

    private final String model;

    public LlmException(String model, String message) {
        super(3000, String.format("LLM [%s] error: %s", model, message));
        this.model = model;
    }

    public LlmException(String model, String message, Throwable cause) {
        super(3000, String.format("LLM [%s] error: %s", model, message), cause);
        this.model = model;
    }

    public String getModel() {
        return model;
    }
}
