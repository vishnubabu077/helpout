package com.instanthelp.helper;

import com.instanthelp.common.ConflictException;
import com.instanthelp.common.NotFoundException;
import com.instanthelp.task.TaskService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The helper's answer to the offer they currently hold. The helper id always comes from the JWT. */
@Service
public class HelperOfferService {

    private final HelperAvailabilityRepository repository;
    private final HelperAvailabilityEvents events;
    private final TaskService tasks;

    public HelperOfferService(HelperAvailabilityRepository repository, HelperAvailabilityEvents events,
                              TaskService tasks) {
        this.repository = repository;
        this.events = events;
        this.tasks = tasks;
    }

    /**
     * OFFERED -> ASSIGNED, conditional on the offer still being OFFERED and not expired. The helper row, the task and
     * the outbox events change in one transaction.
     */
    @Transactional
    public AvailabilityResponse accept(UUID helperId) {
        HelperAvailability row = repository.accept(helperId).orElseThrow(() -> repository.findActiveByHelper(helperId)
                .<RuntimeException>map(active -> new ConflictException("OFFER_EXPIRED", "The offer has expired or is no longer valid"))
                .orElseGet(HelperOfferService::noOffer));
        tasks.offerAccepted(row.currentTaskId(), helperId);
        events.emit(AvailabilityEventType.HelperAssigned, row);
        return AvailabilityResponse.from(row);
    }

    /** OFFERED -> AVAILABLE and the task goes back to OPEN so the requester can match again. */
    @Transactional
    public AvailabilityResponse decline(UUID helperId) {
        var released = repository.decline(helperId).orElseThrow(HelperOfferService::noOffer);
        tasks.offerReleased(released.taskId(), helperId, "OFFER_DECLINED");
        events.emit(AvailabilityEventType.HelperAvailable, released.row()); // back into Redis with the new version
        return AvailabilityResponse.from(released.row());
    }

    private static NotFoundException noOffer() {
        return new NotFoundException("NO_CURRENT_OFFER", "You have no open offer");
    }
}
