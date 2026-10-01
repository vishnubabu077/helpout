package com.instanthelp.helper;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local-only notifier: logs instead of sending a push. Like LoggingOtpSender it exists only in the "local"
 * profile, so other profiles need a real implementation (FCM) before they start.
 */
@Component
@Profile("local")
class LoggingOfferNotifier implements OfferNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingOfferNotifier.class);

    @Override
    public void offerCreated(UUID helperId, UUID taskId, Instant offerExpiresAt) {
        log.info("LOCAL PUSH to helper {}: you have been offered task {}, accept before {}", helperId, taskId, offerExpiresAt);
    }
}
