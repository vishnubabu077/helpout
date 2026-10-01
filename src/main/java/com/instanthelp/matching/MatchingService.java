package com.instanthelp.matching;

import com.instanthelp.common.ConflictException;
import com.instanthelp.common.NotFoundException;
import com.instanthelp.config.AppProperties;
import com.instanthelp.helper.HelperAvailability;
import com.instanthelp.helper.OfferNotifier;
import com.instanthelp.identity.AppUserRepository;
import com.instanthelp.task.Task;
import com.instanthelp.task.TaskRepository;
import com.instanthelp.task.TaskStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds the nearest helper for an OPEN task. Redis only proposes candidates (nearest first); the helper who
 * actually gets the task is the first one whose Postgres row can be reserved by a conditional UPDATE.
 * Deliberately not @Transactional: Redis calls stay outside DB transactions, and every reservation attempt
 * is its own short transaction in {@link OfferReservation}.
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    private final TaskRepository tasks;
    private final AppUserRepository users;
    private final HelperGeoIndex index;
    private final OfferReservation reservation;
    private final OfferNotifier notifier;
    private final AppProperties props;

    public MatchingService(TaskRepository tasks, AppUserRepository users, HelperGeoIndex index,
                           OfferReservation reservation, OfferNotifier notifier, AppProperties props) {
        this.tasks = tasks;
        this.users = users;
        this.index = index;
        this.reservation = reservation;
        this.notifier = notifier;
        this.props = props;
    }

    public MatchResponse match(UUID requesterId, UUID taskId) {
        Task task = tasks.findByIdAndRequesterId(taskId, requesterId)
                .orElseThrow(() -> new NotFoundException("Task not found"));
        if (task.getStatus() != TaskStatus.OPEN) {
            throw new ConflictException("TASK_NOT_OPEN", "Task is " + task.getStatus() + ", not OPEN");
        }

        List<HelperGeoIndex.Candidate> candidates = index.search(task.getCategoryCode(), task.getLatitude(),
                task.getLongitude(), props.matching().searchRadiusM(), props.matching().candidateCount());

        for (HelperGeoIndex.Candidate candidate : candidates) {
            Optional<HelperGeoIndex.CandidateInfo> info = index.info(candidate.availabilityId());
            if (info.isEmpty()) {
                // The hash expired: that helper stopped heart-beating. Clean the stale GEO member lazily.
                safely(() -> index.removeMember(task.getCategoryCode(), candidate.availabilityId()));
                continue;
            }
            if (info.get().helperId().equals(requesterId) || candidate.distanceM() > info.get().maxDistanceM()) {
                continue;
            }

            Optional<HelperAvailability> reserved = reservation.tryReserve(
                    candidate.availabilityId(), taskId, requesterId, task.getCategoryCode());
            if (reserved.isEmpty()) {
                continue; // somebody else got this helper first (or Redis was stale): next nearest
            }

            HelperAvailability offer = reserved.get();
            // After commit: shrink the window in which Redis still lists the reserved helper (the HelperOffered
            // event does the same asynchronously), and tell the helper.
            safely(() -> index.removeFromAll(offer.categoryCodes(), offer.id()));
            safely(() -> notifier.offerCreated(offer.helperId(), taskId, offer.offerExpiresAt()));
            return respond(task, offer, candidate.distanceM());
        }
        throw new NotFoundException("NO_HELPER_AVAILABLE", "No helper is available nearby right now");
    }

    private MatchResponse respond(Task task, HelperAvailability offer, double distanceM) {
        String name = users.findById(offer.helperId()).map(u -> u.getDisplayName()).orElse(null);
        int approximate = (int) Math.max(100, Math.ceil(distanceM / 100.0) * 100);
        return new MatchResponse(task.getId(), TaskStatus.MATCHING, offer.offerExpiresAt(),
                new MatchResponse.OfferedHelper(offer.helperId(), name), approximate);
    }

    /** Post-commit side effects must never turn a successful reservation into an error response. */
    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Post-reservation side effect failed (the reservation stands): {}", e.toString());
        }
    }
}
