package com.instanthelp.helper;

import com.instanthelp.outbox.OutboxService;
import org.springframework.stereotype.Component;

/** Writes availability events to the outbox. Must be called in the same transaction as the row change. */
@Component
public class HelperAvailabilityEvents {

    public static final String AGGREGATE_TYPE = "HELPER_AVAILABILITY";

    private final OutboxService outbox;

    public HelperAvailabilityEvents(OutboxService outbox) {
        this.outbox = outbox;
    }

    public void emit(AvailabilityEventType type, HelperAvailability row) {
        outbox.append(AGGREGATE_TYPE, row.id(), type.name(), row.helperId().toString(),
                HelperAvailabilityEvent.from(row));
    }
}
