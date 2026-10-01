package com.instanthelp.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instanthelp.AbstractMatchingTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MvcResult;

class MatchingApiTest extends AbstractMatchingTest {

    private static final String CATEGORY = "SHOPPING";

    @Autowired StringRedisTemplate redis;

    @Test
    void nearestHelperIsReservedAndTheTaskMovesToMatching() throws Exception {
        // ~0.0036 degrees of latitude is roughly 400 m
        Helper far = helperAvailableAt(11.009, 11.0, CATEGORY, 5000);
        Helper near = helperAvailableAt(11.0036, 11.0, CATEGORY, 5000);
        jdbc.update("update app_user set display_name = 'Alex' where id = ?", near.userId());
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 11.0, 11.0, CATEGORY);

        MvcResult res = mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskStatus").value("MATCHING"))
                .andExpect(jsonPath("$.helper.id").value(near.userId().toString()))
                .andExpect(jsonPath("$.helper.displayName").value("Alex"))
                .andExpect(jsonPath("$.offerExpiresAt").exists())
                .andReturn();

        // approximate distance only: rounded to 100 m, and no coordinates or phone anywhere in the response
        int approximate = json.readTree(res.getResponse().getContentAsString()).get("approximateDistanceM").asInt();
        assertThat(approximate).isBetween(100, 600).isEqualTo(approximate / 100 * 100);
        assertThat(res.getResponse().getContentAsString()).doesNotContainIgnoringCase("latitude")
                .doesNotContainIgnoringCase("longitude").doesNotContain("+1666");

        assertThat(taskStatus(taskId)).isEqualTo("MATCHING");
        assertThat(jdbc.queryForObject("select provider_id from task where id = ?", UUID.class, taskId)).isEqualTo(near.userId());
        var row = availabilityRow(near.availabilityId());
        assertThat(row).containsEntry("status", "OFFERED").containsEntry("version", 2L);
        assertThat(row.get("current_task_id")).isEqualTo(taskId);
        assertThat(row.get("offer_expires_at")).isNotNull();
        assertThat(availabilityRow(far.availabilityId())).containsEntry("status", "AVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where partition_key = ? and event_type = 'HelperOffered'",
                Long.class, near.userId().toString())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from task_event where task_id = ? and event_type = 'HELPER_OFFERED'",
                Long.class, taskId)).isEqualTo(1L);

        // the reserved helper is gone from Redis (synchronously, and again via the HelperOffered event)
        assertThat(index.search(CATEGORY, 11.0, 11.0, 50_000, 100)).extracting(HelperGeoIndex.Candidate::availabilityId)
                .doesNotContain(near.availabilityId()).contains(far.availabilityId());
        awaitNotIndexed(CATEGORY, 11.0, 11.0, near.availabilityId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(index.info(near.availabilityId())).isEmpty());
    }

    @Test
    void noHelperInRangeReturns404AndTheTaskStaysOpen() throws Exception {
        helperAvailableAt(12.5, 12.5, CATEGORY, 5000); // far away from the task
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 12.0, 12.0, CATEGORY);

        mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_HELPER_AVAILABLE"));
        assertThat(taskStatus(taskId)).isEqualTo("OPEN");
    }

    @Test
    void helperWhoServesAnotherCategoryIsNotConsidered() throws Exception {
        helperAvailableAt(13.0, 13.0, "CLEANING", 5000);
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 13.0, 13.0, CATEGORY);

        mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_HELPER_AVAILABLE"));
    }

    @Test
    void helperWhoWillNotTravelThatFarIsSkipped() throws Exception {
        Helper picky = helperAvailableAt(14.0036, 14.0, CATEGORY, 100);      // ~400 m away, max 100 m
        Helper willing = helperAvailableAt(14.018, 14.0, CATEGORY, 5000);    // ~2 km away
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 14.0, 14.0, CATEGORY);

        mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helper.id").value(willing.userId().toString()));
        assertThat(availabilityRow(picky.availabilityId())).containsEntry("status", "AVAILABLE");
    }

    @Test
    void aHelperIsNeverMatchedToTheirOwnTask() throws Exception {
        Helper helper = helperAvailableAt(15.0, 15.0, CATEGORY, 5000);
        UUID taskId = createTask(helper.token(), 15.0, 15.0, CATEGORY);

        mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(helper.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_HELPER_AVAILABLE"));
        assertThat(availabilityRow(helper.availabilityId())).containsEntry("status", "AVAILABLE");
    }

    @Test
    void expiredRedisEntriesAreSkippedAndCleanedUpLazily() throws Exception {
        // a GEO member whose hash expired: the helper's phone died and no "unavailable" event was ever sent
        UUID ghost = UUID.randomUUID();
        redisPlace(CATEGORY, 16.0, 16.0, ghost);
        Helper alive = helperAvailableAt(16.009, 16.0, CATEGORY, 5000);
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 16.0, 16.0, CATEGORY);

        mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helper.id").value(alive.userId().toString()));

        assertThat(index.members(CATEGORY)).doesNotContain(ghost.toString());
    }

    @Test
    void redisCanBeStaleButPostgresDecides() throws Exception {
        Helper stale = helperAvailableAt(17.0036, 17.0, CATEGORY, 5000);   // nearest in Redis...
        Helper next = helperAvailableAt(17.009, 17.0, CATEGORY, 5000);
        // ...but Postgres says it is no longer available (event not yet projected)
        jdbc.update("update helper_availability set status = 'OFFLINE' where id = ?", stale.availabilityId());
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 17.0, 17.0, CATEGORY);

        mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helper.id").value(next.userId().toString()));
        assertThat(availabilityRow(stale.availabilityId())).containsEntry("status", "OFFLINE");
    }

    @Test
    void aHelperWhoStoppedHeartbeatingCannotBeReservedEvenIfRedisStillListsThem() throws Exception {
        Helper dead = helperAvailableAt(18.0036, 18.0, CATEGORY, 5000);
        jdbc.update("update helper_availability set last_heartbeat_at = now() - interval '10 minutes' where id = ?",
                dead.availabilityId());
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 18.0, 18.0, CATEGORY);

        mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_HELPER_AVAILABLE"));
        assertThat(availabilityRow(dead.availabilityId())).containsEntry("status", "AVAILABLE");
    }

    @Test
    void onlyTheRequesterCanMatchAndOnlyOpenTasks() throws Exception {
        helperAvailableAt(19.0, 19.0, CATEGORY, 5000);
        helperAvailableAt(19.0036, 19.0, CATEGORY, 5000);
        String requester = signInNewUser();
        String stranger = signInNewUser();
        UUID taskId = createTask(requester, 19.0, 19.0, CATEGORY);

        mvc.perform(post("/v1/tasks/{id}/match", taskId)).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        assertThat(taskStatus(taskId)).isEqualTo("OPEN");

        mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_OPEN"));
        mvc.perform(post("/v1/tasks/{id}/match", UUID.randomUUID()).header("Authorization", bearer(requester)))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchRadiusIsRespected() throws Exception {
        helperAvailableAt(20.1, 20.0, CATEGORY, 50_000); // ~11 km away, default radius is 5 km
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 20.0, 20.0, CATEGORY);

        mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private void redisPlace(String category, double lat, double lng, UUID member) {
        redis.opsForGeo().add(HelperGeoIndex.geoKey(category), new Point(lng, lat), member.toString());
    }

}
