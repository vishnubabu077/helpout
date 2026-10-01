package com.instanthelp.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instanthelp.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class HelperAvailabilityApiTest extends AbstractIntegrationTest {

    private static final String BASE = "/v1/helpers/me/availability";

    @Test
    void goAvailableCreatesRowAndOutboxEventInOneStep() throws Exception {
        String token = signInNewUser();
        UUID helperId = userId(token);

        goAvailable(token, 54.6872, 25.2797, List.of("SHOPPING", "CLEANING"), 3000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.maxDistanceM").value(3000))
                .andExpect(jsonPath("$.categoryCodes[0]").value("SHOPPING"));

        List<Map<String, Object>> outbox = outboxFor(helperId);
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0)).containsEntry("event_type", "HelperAvailable")
                .containsEntry("partition_key", helperId.toString());
        assertThat(outbox.get(0).get("payload").toString())
                .contains("\"version\": 1", "\"status\": \"AVAILABLE\"", "SHOPPING", "\"maxDistance\": 3000");
    }

    @Test
    void goingAvailableTwiceIsAConflict() throws Exception {
        String token = signInNewUser();
        UUID helperId = userId(token);
        goAvailable(token, 54.0, 25.0, List.of("SHOPPING"), 2000).andExpect(status().isCreated());

        goAvailable(token, 54.0, 25.0, List.of("SHOPPING"), 2000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_AVAILABLE"));

        assertThat(outboxFor(helperId)).hasSize(1); // the rejected call wrote nothing
    }

    @Test
    void unknownCategoryAndBadBodyAreRejected() throws Exception {
        String token = signInNewUser();

        goAvailable(token, 54.0, 25.0, List.of("SHOPPING", "NOT_A_CATEGORY"), 2000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CATEGORY"));

        goAvailable(token, 154.0, 25.0, List.of(), 5)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.latitude").exists())
                .andExpect(jsonPath("$.errors.categoryCodes").exists())
                .andExpect(jsonPath("$.errors.maxDistanceM").exists());
    }

    @Test
    void endpointsRequireAToken() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(delete(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void heartbeatUpdatesLocationBumpsVersionAndWritesOutbox() throws Exception {
        String token = signInNewUser();
        UUID helperId = userId(token);
        goAvailable(token, 54.0, 25.0, List.of("SHOPPING"), 2000).andExpect(status().isCreated());
        var before = jdbc.queryForMap("select last_heartbeat_at from helper_availability where helper_id = ?", helperId);

        mvc.perform(put(BASE + "/location").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 54.5, \"longitude\": 25.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(54.5))
                .andExpect(jsonPath("$.longitude").value(25.5))
                .andExpect(jsonPath("$.version").value(2));

        var after = jdbc.queryForMap("select last_heartbeat_at from helper_availability where helper_id = ?", helperId);
        assertThat(((java.sql.Timestamp) after.get("last_heartbeat_at")).getTime())
                .isGreaterThan(((java.sql.Timestamp) before.get("last_heartbeat_at")).getTime());
        List<Map<String, Object>> outbox = outboxFor(helperId);
        assertThat(outbox).extracting(r -> r.get("event_type")).containsExactly("HelperAvailable", "HelperLocationUpdated");
    }

    @Test
    void heartbeatWithoutAvailabilityIsNotFoundAndCannotTouchSomeoneElsesRow() throws Exception {
        String owner = signInNewUser();
        String stranger = signInNewUser();
        UUID ownerId = userId(owner);
        goAvailable(owner, 54.0, 25.0, List.of("SHOPPING"), 2000).andExpect(status().isCreated());

        mvc.perform(put(BASE + "/location").header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 10.0, \"longitude\": 10.0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));
        mvc.perform(delete(BASE).header("Authorization", bearer(stranger))).andExpect(status().isNotFound());
        mvc.perform(get(BASE).header("Authorization", bearer(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.id").doesNotExist());

        var row = jdbc.queryForMap("select status, version, latitude from helper_availability where helper_id = ?", ownerId);
        assertThat(row).containsEntry("status", "AVAILABLE").containsEntry("version", 1L).containsEntry("latitude", 54.0);
    }

    @Test
    void goOfflineThenGoAvailableAgain() throws Exception {
        String token = signInNewUser();
        UUID helperId = userId(token);
        goAvailable(token, 54.0, 25.0, List.of("SHOPPING"), 2000).andExpect(status().isCreated());

        mvc.perform(delete(BASE).header("Authorization", bearer(token))).andExpect(status().isNoContent());
        mvc.perform(get(BASE).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.status").value("OFFLINE"));
        mvc.perform(delete(BASE).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());

        assertThat(outboxFor(helperId)).extracting(r -> r.get("event_type"))
                .containsExactly("HelperAvailable", "HelperUnavailable");
        assertThat(outboxFor(helperId).get(1).get("payload").toString())
                .contains("\"version\": 2", "\"status\": \"OFFLINE\"");

        goAvailable(token, 54.0, 25.0, List.of("SHOPPING"), 2000).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from helper_availability where helper_id = ?", Long.class, helperId))
                .isEqualTo(2L); // history row + the new one
    }

    @Test
    void cannotGoOfflineWhileHoldingAnOfferOrTask() throws Exception {
        String token = signInNewUser();
        UUID helperId = userId(token);
        goAvailable(token, 54.0, 25.0, List.of("SHOPPING"), 2000).andExpect(status().isCreated());

        for (String busy : List.of("OFFERED", "ASSIGNED")) {
            jdbc.update("update helper_availability set status = ? where helper_id = ? and status <> 'OFFLINE'", busy, helperId);
            mvc.perform(delete(BASE).header("Authorization", bearer(token)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("HELPER_BUSY"));
        }
    }

    @Test
    void getReturnsCurrentState() throws Exception {
        String token = signInNewUser();
        mvc.perform(get(BASE).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"));

        goAvailable(token, 54.0, 25.0, List.of("SHOPPING"), 2000).andExpect(status().isCreated());
        mvc.perform(get(BASE).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---- helpers ----

    private ResultActions goAvailable(String token, double lat, double lng, List<String> categories, int maxDistance)
            throws Exception {
        return mvc.perform(post(BASE).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("latitude", lat, "longitude", lng,
                        "categoryCodes", categories, "maxDistanceM", maxDistance))));
    }

    private List<Map<String, Object>> outboxFor(UUID helperId) {
        return jdbc.queryForList("""
                select event_type, partition_key, payload::text as payload from outbox_event
                 where partition_key = ? order by (payload->>'version')::bigint""", helperId.toString());
    }
}
