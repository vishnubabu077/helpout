package com.instanthelp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instanthelp.matching.HelperGeoIndex;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Helpers for tests that drive the whole flow: go available -> outbox -> Kafka -> Redis -> match.
 * Each test should pick its own coordinates so helpers of other tests are far outside the search radius.
 */
public abstract class AbstractMatchingTest extends AbstractIntegrationTest {

    @Autowired protected HelperGeoIndex index;

    protected record Helper(String token, UUID userId, UUID availabilityId) {}

    /** Signs in a new user, goes available and waits until the helper shows up in Redis (the real async path). */
    protected Helper helperAvailableAt(double lat, double lng, String category, int maxDistanceM) throws Exception {
        String token = signInNewUser();
        MvcResult res = mvc.perform(post("/v1/helpers/me/availability").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("latitude", lat, "longitude", lng,
                                "categoryCodes", List.of(category), "maxDistanceM", maxDistanceM))))
                .andExpect(status().isCreated()).andReturn();
        UUID availabilityId = UUID.fromString(json.readTree(res.getResponse().getContentAsString()).get("id").asText());
        awaitIndexed(category, lat, lng, availabilityId);
        return new Helper(token, userId(token), availabilityId);
    }

    protected void awaitIndexed(String category, double lat, double lng, UUID availabilityId) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(index.search(category, lat, lng, 50_000, 100))
                        .extracting(HelperGeoIndex.Candidate::availabilityId).contains(availabilityId));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(index.info(availabilityId)).isPresent());
    }

    protected void awaitNotIndexed(String category, double lat, double lng, UUID availabilityId) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(index.search(category, lat, lng, 50_000, 100))
                        .extracting(HelperGeoIndex.Candidate::availabilityId).doesNotContain(availabilityId));
    }

    protected UUID createTask(String token, double lat, double lng, String category) throws Exception {
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Help with " + category);
        body.put("categoryCode", category);
        body.put("address", "12 Example Street");
        body.put("latitude", lat);
        body.put("longitude", lng);
        body.put("startTime", start.toString());
        body.put("endTime", start.plus(1, ChronoUnit.HOURS).toString());
        body.put("offeredAmount", "15.00");
        MvcResult res = mvc.perform(post("/v1/tasks").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    protected String taskStatus(UUID taskId) {
        return jdbc.queryForObject("select status from task where id = ?", String.class, taskId);
    }

    protected Map<String, Object> availabilityRow(UUID availabilityId) {
        return jdbc.queryForMap("select * from helper_availability where id = ?", availabilityId);
    }
}
