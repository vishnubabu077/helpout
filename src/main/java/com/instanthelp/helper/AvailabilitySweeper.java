package com.instanthelp.helper;

import com.instanthelp.config.AppProperties;
import com.instanthelp.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Time-based cleanup, safe to run on several instances (rows are claimed with FOR UPDATE SKIP LOCKED):
 * <ul>
 *   <li>offers past offer_expires_at are treated as declined: helper back to AVAILABLE, task back to OPEN;</li>
 *   <li>AVAILABLE rows whose heartbeat is older than the timeout become OFFLINE with a HelperUnavailable event.</li>
 * </ul>
 */
@Component
public class AvailabilitySweeper implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AvailabilitySweeper.class);
    private static final int BATCH = 100;

    private final HelperAvailabilityRepository repository;
    private final HelperAvailabilityEvents events;
    private final TaskService tasks;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public AvailabilitySweeper(HelperAvailabilityRepository repository, HelperAvailabilityEvents events,
                               TaskService tasks, TransactionTemplate tx, AppProperties props) {
        this.repository = repository;
        this.events = events;
        this.tasks = tasks;
        this.tx = tx;
        this.props = props;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::runSafely, props.matching().sweepInterval());
    }

    private void runSafely() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("Availability sweep failed, will retry: {}", e.toString());
        }
    }

    /** Runs both sweeps until nothing is left. Returns how many rows changed. */
    public int sweep() {
        int changed = 0;
        int batch;
        do {
            batch = expireOffersBatch();
            changed += batch;
        } while (batch >= BATCH);
        do {
            batch = offlineStaleBatch();
            changed += batch;
        } while (batch >= BATCH);
        return changed;
    }

    private int expireOffersBatch() {
        Integer n = tx.execute(status -> {
            var released = repository.releaseExpiredOffers(props.matching().heartbeatTimeout(), BATCH);
            for (var r : released) {
                if (r.taskId() != null) {
                    tasks.offerReleased(r.taskId(), r.row().helperId(), "OFFER_EXPIRED");
                }
                events.emit(r.row().status() == AvailabilityStatus.AVAILABLE
                        ? AvailabilityEventType.HelperAvailable : AvailabilityEventType.HelperUnavailable, r.row());
            }
            return released.size();
        });
        return n == null ? 0 : n;
    }

    private int offlineStaleBatch() {
        Integer n = tx.execute(status -> {
            var stale = repository.offlineStaleAvailable(props.matching().heartbeatTimeout(), BATCH);
            stale.forEach(row -> events.emit(AvailabilityEventType.HelperUnavailable, row));
            return stale.size();
        });
        return n == null ? 0 : n;
    }
}
