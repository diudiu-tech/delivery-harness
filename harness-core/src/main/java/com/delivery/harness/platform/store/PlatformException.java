package com.delivery.harness.platform.store;

public class PlatformException extends RuntimeException {
    private final int status;
    private final String code;
    public PlatformException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static PlatformException conflict(String message) { return new PlatformException(409, "VERSION_CONFLICT", message); }
    public static PlatformException invalid(String message) { return new PlatformException(400, "INVALID_REQUEST", message); }
}
