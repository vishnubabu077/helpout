package com.instanthelp.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instanthelp.AbstractIntegrationTest;
import com.instanthelp.helper.AvailabilityStatus;
import com.instanthelp.helper.HelperAvailabilityEvent;
import com.instanthelp.outbox.KafkaTopics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;

/** Kafka -> Redis projection against real Kafka and Redis containers. Each test uses its own area of the map. */
class HelperGeoProjectionTest extends AbstractIntegrationTest {

    private static final String CATEGORY = "GARDENING";

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired StringRedisTemplate redis;
    @Autowired HelperGeoIndex index;
    @Autowired GeoIndexRebuilder rebuilder;

    @Test
    void kafkaEventsAreAppliedInOrderForOneHelper() throws Exception {
        UUID helperId = UUID.randomUUID();
        UUID availabilityId = UUID.randomUUID();

        publish(event(availabilityId, helperId, AvailabilityStatus.AVAILABLE, 10.0, 10.0, 1));
        publish(event(availabilityId, helperId, AvailabilityStatus.AVAILABLE, 10.1, 10.2, 2));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(hash(availabilityId)).containsEntry("version", "2").containsEntry("helperId", helperId.toString())
                    .containsEntry("status", "AVAILABLE").containsEntry("maxDistance", "3000");
            Point p = position(availabilityId);
            assertThat(p.getY()).isCloseTo(10.1, org.assertj.core.data.Offset.offset(0.001));
            assertThat(p.getX()).isCloseTo(10.2, org.assertj.core.data.Offset.offset(0.001));
        });
        assertThat(redis.getExpire(HelperGeoIndex.hashKey(availabilityId))).isBetween(1L, 90L); // heartbeat TTL

        publish(event(availabilityId, helperId, AvailabilityStatus.OFFERED, 10.1, 10.2, 3));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(position(availabilityId)).isNull();
            assertThat(index.hashExists(availabilityId)).isFalse();
        });
    }

    @Test
    void duplicateEventIsANoOp() {
        UUID helperId = UUID.randomUUID();
        UUID availabilityId = UUID.randomUUID();

        assertThat(index.apply(event(availabilityId, helperId, AvailabilityStatus.AVAILABLE, 20.0, 20.0, 1, 1000))).isTrue();
        // same version again, even with different content: ignored
        assertThat(index.apply(event(availabilityId, helperId, AvailabilityStatus.AVAILABLE, 21.0, 21.0, 1, 9999))).isFalse();

        assertThat(hash(availabilityId)).containsEntry("maxDistance", "1000");
        assertThat(position(availabilityId).getY()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void olderVersionAfterNewerIsIgnored() {
        UUID helperId = UUID.randomUUID();
        UUID availabilityId = UUID.randomUUID();

        assertThat(index.apply(event(availabilityId, helperId, AvailabilityStatus.AVAILABLE, 30.2, 30.2, 5))).isTrue();
        assertThat(index.apply(event(availabilityId, helperId, AvailabilityStatus.AVAILABLE, 30.0, 30.0, 4))).isFalse();

        assertThat(hash(availabilityId)).containsEntry("version", "5");
        assertThat(position(availabilityId).getY()).isCloseTo(30.2, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void lateAvailableEventCannotResurrectARemovedHelper() {
        UUID helperId = UUID.randomUUID();
        UUID availabilityId = UUID.randomUUID();

        assertThat(index.apply(event(availabilityId, helperId, AvailabilityStatus.AVAILABLE, 40.0, 40.0, 1))).isTrue();
        assertThat(index.apply(event(availabilityId, helperId, AvailabilityStatus.OFFERED, 40.0, 40.0, 3))).isTrue();
        assertThat(index.hashExists(availabilityId)).isFalse();

        // v2 (a heartbeat) arrives after v3 (the offer): must not put the helper back
        assertThat(index.apply(event(availabilityId, helperId, AvailabilityStatus.AVAILABLE, 40.0, 40.0, 2))).isFalse();
        assertThat(position(availabilityId)).isNull();
        assertThat(index.hashExists(availabilityId)).isFalse();
    }

    @Test
    void helperDisappearsOnItsOwnWhenHeartbeatsStop() {
        UUID availabilityId = UUID.randomUUID();
        index.apply(event(availabilityId, UUID.randomUUID(), AvailabilityStatus.AVAILABLE, 50.0, 50.0, 1),
                Duration.ofSeconds(1), false);
        assertThat(index.hashExists(availabilityId)).isTrue();

        // no "unavailable" event is ever sent: the TTL alone removes the hash, so matching will skip it
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(index.hashExists(availabilityId)).isFalse());
        assertThat(index.info(availabilityId)).isEmpty();
    }

    @Test
    void goingAvailableAndOfflineFlowsThroughOutboxKafkaToRedis() throws Exception {
        String token = signInNewUser();
        mvc.perform(post("/v1/helpers/me/availability").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("latitude", 60.0, "longitude", 60.0,
                                "categoryCodes", List.of(CATEGORY), "maxDistanceM", 3000))))
                .andExpect(status().isCreated());
        UUID availabilityId = availabilityOf(userId(token));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(index.search(CATEGORY, 60.0, 60.0, 500, 10)).extracting(HelperGeoIndex.Candidate::availabilityId)
                    .contains(availabilityId);
            assertThat(index.info(availabilityId)).isPresent();
        });
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where partition_key = ? and published_at is null",
                Long.class, userId(token).toString())).isZero();

        mvc.perform(delete("/v1/helpers/me/availability").header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(index.search(CATEGORY, 60.0, 60.0, 500, 10)).extracting(HelperGeoIndex.Candidate::availabilityId)
                    .doesNotContain(availabilityId);
            assertThat(index.hashExists(availabilityId)).isFalse();
        });
    }

    @Test
    void rebuildRestoresFreshAvailableHelpersAndDropsOrphans() throws Exception {
        UUID fresh = createAvailableRow(70.0, 70.0, "0 seconds");
        UUID stale = createAvailableRow(70.0, 70.0, "10 minutes");
        UUID offered = createAvailableRow(70.0, 70.0, "0 seconds");
        jdbc.update("update helper_availability set status = 'OFFERED' where id = ?", offered);
        UUID orphan = UUID.randomUUID(); // in a GEO set but without a hash: left behind by an expired helper
        redis.opsForGeo().add(HelperGeoIndex.geoKey(CATEGORY), new Point(70.0, 70.0), orphan.toString());

        // Redis lost everything it knew about these helpers
        for (UUID id : List.of(fresh, stale, offered)) {
            redis.delete(List.of(HelperGeoIndex.hashKey(id), HelperGeoIndex.hashKey(id) + ":version"));
            index.removeMember(CATEGORY, id);
        }

        mvc.perform(post("/v1/admin/matching/rebuild").header("Authorization", bearer(signInAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helpersIndexed").isNumber())
                .andExpect(jsonPath("$.orphansRemoved").isNumber());

        List<UUID> found = index.search(CATEGORY, 70.0, 70.0, 1000, 50).stream()
                .map(HelperGeoIndex.Candidate::availabilityId).toList();
        assertThat(found).contains(fresh).doesNotContain(stale, offered, orphan);
        assertThat(index.info(fresh)).isPresent();
        assertThat(index.info(fresh).get().maxDistanceM()).isEqualTo(3000);
        assertThat(redis.getExpire(HelperGeoIndex.hashKey(fresh))).isBetween(1L, 90L);
        assertThat(rebuilder.rebuild().orphansRemoved()).isZero(); // idempotent
    }

    @Test
    void rebuildEndpointIsAdminOnly() throws Exception {
        mvc.perform(post("/v1/admin/matching/rebuild").header("Authorization", bearer(signInNewUser())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/admin/matching/rebuild")).andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private UUID createAvailableRow(double lat, double lng, String heartbeatAge) throws Exception {
        UUID userId = userId(signInNewUser());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into helper_availability (id, helper_id, status, latitude, longitude, category_codes,
                                                 max_distance_m, last_heartbeat_at, version)
                values (?, ?, 'AVAILABLE', ?, ?, array['GARDENING'], 3000, now() - cast(? as interval), 7)""",
                id, userId, lat, lng, heartbeatAge);
        return id;
    }

    private UUID availabilityOf(UUID helperId) {
        return jdbc.queryForObject("select id from helper_availability where helper_id = ? and status = 'AVAILABLE'",
                UUID.class, helperId);
    }

    private void publish(HelperAvailabilityEvent event) throws Exception {
        kafka.send(KafkaTopics.HELPER_AVAILABILITY, event.helperId().toString(), json.writeValueAsString(event)).get();
    }

    private Map<Object, Object> hash(UUID availabilityId) {
        return redis.opsForHash().entries(HelperGeoIndex.hashKey(availabilityId));
    }

    private Point position(UUID availabilityId) {
        List<Point> points = redis.opsForGeo().position(HelperGeoIndex.geoKey(CATEGORY), availabilityId.toString());
        return points == null || points.isEmpty() ? null : points.get(0);
    }

    private static HelperAvailabilityEvent event(UUID availabilityId, UUID helperId, AvailabilityStatus status,
                                                 double lat, double lng, long version) {
        return event(availabilityId, helperId, status, lat, lng, version, 3000);
    }

    private static HelperAvailabilityEvent event(UUID availabilityId, UUID helperId, AvailabilityStatus status,
                                                 double lat, double lng, long version, int maxDistance) {
        return new HelperAvailabilityEvent(availabilityId, helperId, status, lat, lng, List.of(CATEGORY),
                maxDistance, version, Instant.now());
    }
}
