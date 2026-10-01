package com.instanthelp.matching;

import com.instanthelp.config.AppProperties;
import com.instanthelp.helper.AvailabilityEventType;
import com.instanthelp.helper.HelperAvailability;
import com.instanthelp.helper.HelperAvailabilityEvents;
import com.instanthelp.helper.HelperAvailabilityRepository;
import com.instanthelp.task.TaskService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One reservation attempt = one transaction: the conditional UPDATE on the helper, the task moving to MATCHING and
 * the HelperOffered outbox row either all happen or none do. If the helper was taken meanwhile the UPDATE returns
 * nothing and the transaction commits nothing.
 */
@Component
public class OfferReservation {

    private final HelperAvailabilityRepository availability;
    private final HelperAvailabilityEvents events;
    private final TaskService tasks;
    private final AppProperties props;

    public OfferReservation(HelperAvailabilityRepository availability, HelperAvailabilityEvents events,
                            TaskService tasks, AppProperties props) {
        this.availability = availability;
        this.events = events;
        this.tasks = tasks;
        this.props = props;
    }

    @Transactional
    public Optional<HelperAvailability> tryReserve(UUID availabilityId, UUID taskId, UUID requesterId, String categoryCode) {
        Optional<HelperAvailability> reserved = availability.reserve(availabilityId, taskId, requesterId, categoryCode,
                props.matching().offerTtl(), props.matching().heartbeatTimeout());
        if (reserved.isEmpty()) {
            return reserved;
        }
        // Throws TASK_NOT_OPEN (409) if another request already took this task: rolls the reservation back too.
        tasks.offerTo(taskId, requesterId, reserved.get().helperId());
        events.emit(AvailabilityEventType.HelperOffered, reserved.get());
        return reserved;
    }
}
