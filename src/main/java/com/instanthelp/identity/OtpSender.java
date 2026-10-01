package com.instanthelp.identity;

/** Delivery channel for one-time codes. Production: an SMS provider adapter (e.g. Twilio Verify). */
public interface OtpSender {
    void send(String phoneE164, String code);
}
