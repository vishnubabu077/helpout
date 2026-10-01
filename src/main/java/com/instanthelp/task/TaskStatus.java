package com.instanthelp.task;

/**
 * Full lifecycle from the design doc. OPEN, MATCHING (a helper holds an offer), ASSIGNED and CANCELLED are
 * produced so far; the rest arrive with completion/payment.
 */
public enum TaskStatus {
    DRAFT, PENDING_REVIEW, OPEN, MATCHING, ASSIGNED, CLAIMED, IN_PROGRESS, COMPLETION_PENDING_REQUESTER,
    COMPLETED, PAYMENT_PENDING, SETTLED, PAYMENT_FAILED, REFUND_PENDING,
    DISPUTED, CANCELLED, EXPIRED, REJECTED
}
