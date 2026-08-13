package com.delivery.harness.common.exception;

public class ToolException extends HarnessException {

    private final String toolName;

    public ToolException(String toolName, String message) {
        super(2000, String.format("Tool [%s] error: %s", toolName, message));
        this.toolName = toolName;
    }

    public ToolException(String toolName, String message, Throwable cause) {
        super(2000, String.format("Tool [%s] error: %s", toolName, message), cause);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
