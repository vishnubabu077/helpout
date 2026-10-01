package com.instanthelp.common;

/** The request conflicts with the current state of the resource (HTTP 409), with a stable machine-readable code. */
public class ConflictException extends RuntimeException {
    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
