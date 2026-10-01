package com.instanthelp.helper;

public enum AvailabilityStatus {
    /** Ready to help and discoverable through Redis. */
    AVAILABLE,
    /** Reserved for one task; waiting for the helper to accept or decline until offer_expires_at. */
    OFFERED,
    /** Accepted a task. */
    ASSIGNED,
    /** History row: went offline, timed out, or was released. */
    OFFLINE
}
