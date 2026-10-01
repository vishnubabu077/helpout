package com.instanthelp.helper;

import java.time.Instant;
import java.util.UUID;

/** Tells a helper they have been offered a task (push notification). Called after the offer is committed. */
public interface OfferNotifier {

    void offerCreated(UUID helperId, UUID taskId, Instant offerExpiresAt);
}
