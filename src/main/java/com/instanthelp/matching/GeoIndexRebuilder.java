package com.instanthelp.matching;

import com.instanthelp.config.AppProperties;
import com.instanthelp.helper.HelperAvailability;
import com.instanthelp.helper.HelperAvailabilityEvent;
import com.instanthelp.helper.HelperAvailabilityRepository;
import com.instanthelp.task.TaskCategory;
import com.instanthelp.task.TaskCategoryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the Redis candidate index from Postgres: every AVAILABLE row with a fresh heartbeat is (re)written,
 * and GEO members that have no hash (expired or orphaned) are dropped. Runs at startup and on demand
 * (POST /v1/admin/matching/rebuild). Safe to run while events are flowing: rows go through the same
 * version-checked script as Kafka events, so a newer event always wins.
 */
@Component
public class GeoIndexRebuilder {

    private static final Logger log = LoggerFactory.getLogger(GeoIndexRebuilder.class);

    public record Result(int helpersIndexed, int orphansRemoved) {}

    private final HelperAvailabilityRepository availability;
    private final TaskCategoryRepository categories;
    private final HelperGeoIndex index;
    private final AppProperties props;

    public GeoIndexRebuilder(HelperAvailabilityRepository availability, TaskCategoryRepository categories,
                             HelperGeoIndex index, AppProperties props) {
        this.availability = availability;
        this.categories = categories;
        this.index = index;
        this.props = props;
    }

    /** Redis being unavailable at startup must not stop the app; the on-demand endpoint can be used later. */
    @EventListener(ApplicationReadyEvent.class)
    void rebuildOnStartup() {
        try {
            Result result = rebuild();
            log.info("Redis helper index rebuilt at startup: {}", result);
        } catch (RuntimeException e) {
            log.warn("Could not rebuild the Redis helper index at startup: {}", e.toString());
        }
    }

    public Result rebuild() {
        Duration timeout = props.matching().heartbeatTimeout();
        Instant now = Instant.now();
        Set<UUID> indexed = new HashSet<>();
        int count = 0;

        List<HelperAvailability> fresh = availability.findFreshAvailable(timeout);
        for (HelperAvailability row : fresh) {
            Duration remaining = timeout.minus(Duration.between(row.lastHeartbeatAt(), now));
            if (remaining.isNegative() || remaining.isZero()) {
                continue; // went stale while we were reading
            }
            index.apply(HelperAvailabilityEvent.from(row), remaining, true);
            indexed.add(row.id());
            count++;
        }

        int orphans = 0;
        for (String code : categories.findAll().stream().map(TaskCategory::getCode).toList()) {
            for (String member : index.members(code)) {
                UUID id = UUID.fromString(member);
                if (!indexed.contains(id) && !index.hashExists(id)) {
                    index.removeMember(code, id);
                    orphans++;
                }
            }
        }
        return new Result(count, orphans);
    }
}
