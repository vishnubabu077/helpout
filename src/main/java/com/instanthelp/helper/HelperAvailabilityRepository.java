package com.instanthelp.helper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All state changes are single conditional statements (UPDATE ... WHERE status = ... RETURNING *), so
 * concurrent writers are serialized by the row lock and exactly one of them sees a row come back.
 * Time is always taken from the database clock.
 */
@Repository
public class HelperAvailabilityRepository {

    static final String ACTIVE = "('AVAILABLE', 'OFFERED', 'ASSIGNED')";

    private final JdbcClient jdbc;

    public HelperAvailabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static final RowMapper<HelperAvailability> MAPPER = HelperAvailabilityRepository::map;

    static HelperAvailability map(ResultSet rs, int rowNum) throws SQLException {
        String[] categories = (String[]) rs.getArray("category_codes").getArray();
        return new HelperAvailability(
                rs.getObject("id", UUID.class),
                rs.getObject("helper_id", UUID.class),
                AvailabilityStatus.valueOf(rs.getString("status")),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                List.of(categories),
                rs.getInt("max_distance_m"),
                rs.getObject("current_task_id", UUID.class),
                instant(rs, "offer_expires_at"),
                instant(rs, "last_heartbeat_at"),
                rs.getLong("version"));
    }

    static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public Optional<HelperAvailability> findById(UUID id) {
        return jdbc.sql("select * from helper_availability where id = :id")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<HelperAvailability> findActiveByHelper(UUID helperId) {
        return jdbc.sql("select * from helper_availability where helper_id = :helperId and status in " + ACTIVE)
                .param("helperId", helperId).query(MAPPER).optional();
    }

    /** AVAILABLE rows whose heartbeat is still within the timeout: what Redis should contain. */
    public List<HelperAvailability> findFreshAvailable(java.time.Duration heartbeatTimeout) {
        return jdbc.sql("""
                        select * from helper_availability
                         where status = 'AVAILABLE'
                           and last_heartbeat_at > now() - make_interval(secs => :timeoutSeconds)""")
                .param("timeoutSeconds", (double) heartbeatTimeout.toSeconds())
                .query(MAPPER).list();
    }

    /** Throws DuplicateKeyException if the helper already has an active row (partial unique index). */
    public HelperAvailability insertAvailable(UUID helperId, double lat, double lng, List<String> categories,
                                              int maxDistanceM) {
        return jdbc.sql("""
                        insert into helper_availability
                            (id, helper_id, status, latitude, longitude, category_codes, max_distance_m, last_heartbeat_at)
                        values (:id, :helperId, 'AVAILABLE', :lat, :lng, cast(:categories as text[]), :maxDistance, now())
                        returning *""")
                .param("id", UUID.randomUUID())
                .param("helperId", helperId)
                .param("lat", lat)
                .param("lng", lng)
                .param("categories", categories.toArray(new String[0]))
                .param("maxDistance", maxDistanceM)
                .query(MAPPER).single();
    }

    /** Heartbeat + location. Allowed in any active state so a busy helper stays fresh. */
    public Optional<HelperAvailability> updateLocation(UUID helperId, double lat, double lng) {
        return jdbc.sql("""
                        update helper_availability
                           set latitude = :lat, longitude = :lng, last_heartbeat_at = now(),
                               version = version + 1, updated_at = now()
                         where helper_id = :helperId and status in """ + ACTIVE + " returning *")
                .param("lat", lat)
                .param("lng", lng)
                .param("helperId", helperId)
                .query(MAPPER).optional();
    }

    /**
     * THE assignment decision: reserve one helper for one task with a single conditional UPDATE. Of any number of
     * concurrent callers, the row lock lets exactly one see the row still AVAILABLE; the others get nothing back.
     * Also re-checks in Postgres what Redis only suggested: the heartbeat is fresh, the helper is not the
     * requester and does serve the task's category.
     */
    public Optional<HelperAvailability> reserve(UUID availabilityId, UUID taskId, UUID requesterId, String categoryCode,
                                                java.time.Duration offerTtl, java.time.Duration heartbeatTimeout) {
        return jdbc.sql("""
                        update helper_availability
                           set status = 'OFFERED', current_task_id = :taskId,
                               offer_expires_at = now() + make_interval(secs => :offerTtl),
                               version = version + 1, updated_at = now()
                         where id = :id
                           and status = 'AVAILABLE'
                           and last_heartbeat_at > now() - make_interval(secs => :heartbeatTimeout)
                           and helper_id <> :requesterId
                           and :category = any (category_codes)
                         returning *""")
                .param("id", availabilityId)
                .param("taskId", taskId)
                .param("requesterId", requesterId)
                .param("category", categoryCode)
                .param("offerTtl", offerTtl.toMillis() / 1000.0)
                .param("heartbeatTimeout", heartbeatTimeout.toMillis() / 1000.0)
                .query(MAPPER).optional();
    }

    /** Only from AVAILABLE. */
    public Optional<HelperAvailability> goOffline(UUID helperId) {
        return jdbc.sql("""
                        update helper_availability
                           set status = 'OFFLINE', version = version + 1, updated_at = now()
                         where helper_id = :helperId and status = 'AVAILABLE'
                         returning *""")
                .param("helperId", helperId)
                .query(MAPPER).optional();
    }
}
