package com.instanthelp.identity;

public class InvalidOtpException extends RuntimeException {
    public InvalidOtpException() {
        super("Invalid or expired code");
    }
}
