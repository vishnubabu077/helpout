package com.instanthelp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class TaskApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void authenticatedUserCanCreateAndReadHelpRequest() throws Exception {
        String token = signIn("+15555550201");

        MvcResult created = mvc.perform(post("/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validTask())))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andReturn();

        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(get("/v1/tasks/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Help carrying groceries"));

        mvc.perform(get("/v1/tasks/mine").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void createWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validTask())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = signIn("+15555550202");
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        String token = signIn("+15555550203");
        Map<String, Object> body = validTask();
        body.put("title", "");
        body.put("offeredAmount", "0");
        mvc.perform(post("/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.offeredAmount").exists());
    }

    @Test
    void timeWindowShorterThanMinimumIsRejected() throws Exception {
        String token = signIn("+15555550204");
        Map<String, Object> body = validTask();
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        body.put("startTime", start.toString());
        body.put("endTime", start.plus(10, ChronoUnit.MINUTES).toString());
        mvc.perform(post("/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TASK_TOO_SHORT"));
    }

    @Test
    void unknownCategoryIsRejected() throws Exception {
        String token = signIn("+15555550205");
        Map<String, Object> body = validTask();
        body.put("categoryCode", "NOT_A_CATEGORY");
        mvc.perform(post("/v1/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CATEGORY"));
    }

    @Test
    void otherUsersCannotReadMyTask() throws Exception {
        String owner = signIn("+15555550206");
        String stranger = signIn("+15555550207");

        MvcResult created = mvc.perform(post("/v1/tasks")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validTask())))
                .andExpect(status().isCreated())
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(get("/v1/tasks/{id}", id).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongOtpIsRejected() throws Exception {
        String phone = "+15555550208";
        mvc.perform(post("/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"111111\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_OTP"));
    }

    // ---- helpers ----

    private Map<String, Object> validTask() {
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Help carrying groceries");
        body.put("description", "About 6 bags, third floor");
        body.put("categoryCode", "SHOPPING");
        body.put("address", "12 Example Street");
        body.put("latitude", "54.6872");
        body.put("longitude", "25.2797");
        body.put("startTime", start.toString());
        body.put("endTime", start.plus(1, ChronoUnit.HOURS).toString());
        body.put("offeredAmount", "15.00");
        return body;
    }
}
