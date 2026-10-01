package com.instanthelp.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local-only sender: logs the code instead of sending an SMS.
 * Only active with the "local" profile, so a non-local run without a real sender fails at startup
 * instead of silently logging codes.
 */
@Component
@Profile("local")
class LoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(String phoneE164, String code) {
        log.info("LOCAL OTP for {} = {}", phoneE164, code);
    }
}
