package com.instanthelp.task;

/** Full lifecycle from the design doc. Only OPEN is produced so far; the rest arrive with claim/complete/payment. */
public enum TaskStatus {
    DRAFT, PENDING_REVIEW, OPEN, CLAIMED, IN_PROGRESS, COMPLETION_PENDING_REQUESTER,
    COMPLETED, PAYMENT_PENDING, SETTLED, PAYMENT_FAILED, REFUND_PENDING,
    DISPUTED, CANCELLED, EXPIRED, REJECTED
}
