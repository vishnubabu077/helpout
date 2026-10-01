package com.instanthelp.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instanthelp.AbstractMatchingTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

@ExtendWith(OutputCaptureExtension.class)
class HelperOfferApiTest extends AbstractMatchingTest {

    private static final String CATEGORY = "PET_CARE";
    private static final String ACCEPT = "/v1/helpers/me/offers/current/accept";
    private static final String DECLINE = "/v1/helpers/me/offers/current/decline";

    @Autowired AvailabilitySweeper sweeper;

    // ---- accept ----

    @Test
    void helperAcceptsAndBothRowsBecomeAssigned() throws Exception {
        Helper helper = helperAvailableAt(41.0, 41.0, CATEGORY, 5000);
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 41.0, 41.0, CATEGORY);
        match(requester, taskId).andExpect(status().isOk());

        mvc.perform(post(ACCEPT).header("Authorization", bearer(helper.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.currentTaskId").value(taskId.toString()))
                .andExpect(jsonPath("$.version").value(3));

        assertThat(taskStatus(taskId)).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("select provider_id from task where id = ?", UUID.class, taskId)).isEqualTo(helper.userId());
        assertThat(availabilityRow(helper.availabilityId())).containsEntry("status", "ASSIGNED");
        assertThat(availabilityRow(helper.availabilityId()).get("offer_expires_at")).isNull();

        assertThat(eventTypes(helper.userId().toString())).containsExactly("HelperAvailable", "HelperOffered", "HelperAssigned");
        assertThat(eventTypes(taskId.toString())).containsExactly("TaskAssigned"); // task event, keyed by taskId
        assertThat(jdbc.queryForObject("select count(*) from task_event where task_id = ? and event_type = 'HELPER_ACCEPTED'",
                Long.class, taskId)).isEqualTo(1L);

        // an assigned helper cannot just go offline, but keeps heart-beating
        mvc.perform(delete("/v1/helpers/me/availability").header("Authorization", bearer(helper.token())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HELPER_BUSY"));
        mvc.perform(put("/v1/helpers/me/availability/location").header("Authorization", bearer(helper.token()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"latitude\": 41.001, \"longitude\": 41.0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSIGNED"));
        // and never becomes a candidate again
        awaitNotIndexed(CATEGORY, 41.0, 41.0, helper.availabilityId());
    }

    @Test
    void acceptingAnExpiredOfferIsRejectedAndChangesNothing() throws Exception {
        Helper helper = helperAvailableAt(42.0, 42.0, CATEGORY, 5000);
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 42.0, 42.0, CATEGORY);
        match(requester, taskId).andExpect(status().isOk());
        jdbc.update("update helper_availability set offer_expires_at = now() - interval '1 second' where id = ?",
                helper.availabilityId());

        mvc.perform(post(ACCEPT).header("Authorization", bearer(helper.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));

        assertThat(availabilityRow(helper.availabilityId())).containsEntry("status", "OFFERED");
        assertThat(taskStatus(taskId)).isEqualTo("MATCHING");
    }

    @Test
    void acceptWithoutAnOfferIsRejected() throws Exception {
        String nobody = signInNewUser();
        mvc.perform(post(ACCEPT).header("Authorization", bearer(nobody)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NO_CURRENT_OFFER"));

        Helper idle = helperAvailableAt(43.0, 43.0, CATEGORY, 5000); // available, but nobody offered them anything
        mvc.perform(post(ACCEPT).header("Authorization", bearer(idle.token())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));
        mvc.perform(post(ACCEPT)).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherHelperCannotAnswerSomeoneElsesOffer() throws Exception {
        Helper offered = helperAvailableAt(44.0, 44.0, CATEGORY, 5000);
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 44.0, 44.0, CATEGORY);
        match(requester, taskId).andExpect(status().isOk());
        Helper bystander = helperAvailableAt(44.5, 44.5, CATEGORY, 5000);

        mvc.perform(post(ACCEPT).header("Authorization", bearer(bystander.token()))).andExpect(status().isConflict());
        mvc.perform(post(DECLINE).header("Authorization", bearer(bystander.token()))).andExpect(status().isNotFound());

        assertThat(availabilityRow(offered.availabilityId())).containsEntry("status", "OFFERED");
        assertThat(taskStatus(taskId)).isEqualTo("MATCHING");
    }

    // ---- decline ----

    @Test
    void declineReturnsTheHelperToRedisAndReopensTheTask() throws Exception {
        Helper helper = helperAvailableAt(45.0, 45.0, CATEGORY, 5000);
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 45.0, 45.0, CATEGORY);
        match(requester, taskId).andExpect(status().isOk());
        awaitNotIndexed(CATEGORY, 45.0, 45.0, helper.availabilityId());

        mvc.perform(post(DECLINE).header("Authorization", bearer(helper.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.currentTaskId").doesNotExist())
                .andExpect(jsonPath("$.version").value(3));

        assertThat(taskStatus(taskId)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select provider_id from task where id = ?", UUID.class, taskId)).isNull();
        assertThat(eventTypes(helper.userId().toString())).containsExactly("HelperAvailable", "HelperOffered", "HelperAvailable");
        awaitIndexed(CATEGORY, 45.0, 45.0, helper.availabilityId()); // outbox -> Kafka -> Redis put them back

        match(requester, taskId).andExpect(status().isOk()) // the requester can match again
                .andExpect(jsonPath("$.helper.id").value(helper.userId().toString()));
    }

    @Test
    void declineWithoutAnOfferIsNotFound() throws Exception {
        Helper idle = helperAvailableAt(46.0, 46.0, CATEGORY, 5000);
        mvc.perform(post(DECLINE).header("Authorization", bearer(idle.token())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NO_CURRENT_OFFER"));
    }

    // ---- sweeper ----

    @Test
    void sweeperTreatsAnExpiredOfferAsDeclined() throws Exception {
        Helper helper = helperAvailableAt(47.0, 47.0, CATEGORY, 5000);
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 47.0, 47.0, CATEGORY);
        match(requester, taskId).andExpect(status().isOk());
        jdbc.update("update helper_availability set offer_expires_at = now() - interval '1 second' where id = ?",
                helper.availabilityId());

        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);

        assertThat(availabilityRow(helper.availabilityId())).containsEntry("status", "AVAILABLE").containsEntry("version", 3L);
        assertThat(availabilityRow(helper.availabilityId()).get("current_task_id")).isNull();
        assertThat(taskStatus(taskId)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select count(*) from task_event where task_id = ? and event_type = 'OFFER_EXPIRED'",
                Long.class, taskId)).isEqualTo(1L);
        assertThat(eventTypes(helper.userId().toString())).containsExactly("HelperAvailable", "HelperOffered", "HelperAvailable");
        awaitIndexed(CATEGORY, 47.0, 47.0, helper.availabilityId());
        assertThat(sweeper.sweep()).isZero(); // nothing left to do
    }

    @Test
    void sweeperTakesAnExpiredOfferOfADeadHelperOfflineInsteadOfAvailable() throws Exception {
        Helper helper = helperAvailableAt(48.0, 48.0, CATEGORY, 5000);
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 48.0, 48.0, CATEGORY);
        match(requester, taskId).andExpect(status().isOk());
        jdbc.update("""
                update helper_availability set offer_expires_at = now() - interval '1 second',
                       last_heartbeat_at = now() - interval '10 minutes' where id = ?""", helper.availabilityId());

        sweeper.sweep();

        assertThat(availabilityRow(helper.availabilityId())).containsEntry("status", "OFFLINE");
        assertThat(taskStatus(taskId)).isEqualTo("OPEN");
        assertThat(eventTypes(helper.userId().toString())).containsExactly("HelperAvailable", "HelperOffered", "HelperUnavailable");
    }

    @Test
    void sweeperTakesHelpersWithoutHeartbeatOffline() throws Exception {
        Helper dead = helperAvailableAt(49.0, 49.0, CATEGORY, 5000);
        Helper alive = helperAvailableAt(49.0, 49.001, CATEGORY, 5000);
        jdbc.update("update helper_availability set last_heartbeat_at = now() - interval '10 minutes' where id = ?",
                dead.availabilityId());

        sweeper.sweep();

        assertThat(availabilityRow(dead.availabilityId())).containsEntry("status", "OFFLINE").containsEntry("version", 2L);
        assertThat(availabilityRow(alive.availabilityId())).containsEntry("status", "AVAILABLE");
        assertThat(eventTypes(dead.userId().toString())).containsExactly("HelperAvailable", "HelperUnavailable");
        awaitNotIndexed(CATEGORY, 49.0, 49.0, dead.availabilityId()); // the event removes them from Redis
        awaitIndexed(CATEGORY, 49.0, 49.0, alive.availabilityId());
        // the dead helper can come back explicitly
        mvc.perform(post("/v1/helpers/me/availability").header("Authorization", bearer(dead.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("latitude", 49.0, "longitude", 49.0,
                                "categoryCodes", List.of(CATEGORY), "maxDistanceM", 3000))))
                .andExpect(status().isCreated());
    }

    // ---- release when the task closes ----

    @Test
    void cancellingAnAssignedTaskReleasesTheHelper() throws Exception {
        Helper helper = helperAvailableAt(50.0, 50.0, CATEGORY, 5000);
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 50.0, 50.0, CATEGORY);
        match(requester, taskId).andExpect(status().isOk());
        mvc.perform(post(ACCEPT).header("Authorization", bearer(helper.token()))).andExpect(status().isOk());

        mvc.perform(post("/v1/tasks/{id}/cancel", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(availabilityRow(helper.availabilityId())).containsEntry("status", "OFFLINE");
        assertThat(eventTypes(helper.userId().toString()))
                .containsExactly("HelperAvailable", "HelperOffered", "HelperAssigned", "HelperUnavailable");
        assertThat(eventTypes(taskId.toString())).containsExactly("TaskAssigned", "TaskCancelled");
        // the helper goes available again explicitly
        mvc.perform(post("/v1/helpers/me/availability").header("Authorization", bearer(helper.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("latitude", 50.0, "longitude", 50.0,
                                "categoryCodes", List.of(CATEGORY), "maxDistanceM", 3000))))
                .andExpect(status().isCreated());
    }

    @Test
    void cancellingWhileAnOfferIsOpenReleasesTheOfferedHelper() throws Exception {
        Helper helper = helperAvailableAt(51.0, 51.0, CATEGORY, 5000);
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 51.0, 51.0, CATEGORY);
        match(requester, taskId).andExpect(status().isOk());

        mvc.perform(post("/v1/tasks/{id}/cancel", taskId).header("Authorization", bearer(requester))).andExpect(status().isOk());

        assertThat(availabilityRow(helper.availabilityId())).containsEntry("status", "OFFLINE");
        mvc.perform(post(ACCEPT).header("Authorization", bearer(helper.token())))
                .andExpect(status().isNotFound()); // nothing left to accept
    }

    @Test
    void cancelRulesAndOwnership() throws Exception {
        String requester = signInNewUser();
        String stranger = signInNewUser();
        UUID taskId = createTask(requester, 52.0, 52.0, CATEGORY);

        mvc.perform(post("/v1/tasks/{id}/cancel", taskId).header("Authorization", bearer(stranger))).andExpect(status().isNotFound());
        mvc.perform(post("/v1/tasks/{id}/cancel", taskId)).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/tasks/{id}/cancel", taskId).header("Authorization", bearer(requester))).andExpect(status().isOk());
        mvc.perform(post("/v1/tasks/{id}/cancel", taskId).header("Authorization", bearer(requester)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_NOT_CANCELLABLE"));
    }

    // ---- push notification ----

    @Test
    void offeredHelperIsNotifiedThroughTheNotifierInterface(CapturedOutput output) throws Exception {
        Helper helper = helperAvailableAt(53.0, 53.0, CATEGORY, 5000);
        String requester = signInNewUser();
        UUID taskId = createTask(requester, 53.0, 53.0, CATEGORY);

        match(requester, taskId).andExpect(status().isOk());

        assertThat(output.getAll()).contains("LOCAL PUSH to helper " + helper.userId() + ": you have been offered task " + taskId);
    }

    // ---- helpers ----

    private ResultActions match(String requesterToken, UUID taskId) throws Exception {
        return mvc.perform(post("/v1/tasks/{id}/match", taskId).header("Authorization", bearer(requesterToken)));
    }

    /** Outbox events for an aggregate key (helperId or taskId), oldest first. */
    private List<String> eventTypes(String partitionKey) {
        return jdbc.queryForList("""
                select event_type from outbox_event where partition_key = ?
                 order by created_at, (payload->>'version')::bigint nulls last, id""", String.class, partitionKey);
    }
}
