package com.instanthelp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * Requires Docker. One throwaway PostgreSQL, Kafka and Redis are shared by every integration test class (and so
 * is the Spring context). Uses the "local" profile: dev secret + OTP 000000. Tests isolate themselves with unique phone
 * numbers, ids and coordinates instead of cleaning the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        Startables.deepStart(POSTGRES, KAFKA, REDIS).join();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Tests that age rows on purpose call AvailabilitySweeper.sweep() themselves; the scheduled run would race them.
        registry.add("app.matching.sweep-interval", () -> "1h");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;

    private static final AtomicLong PHONE_SEQ = new AtomicLong(1_000_000);

    /** A phone number no other test uses (the database is shared between test classes). */
    protected static String newPhone() {
        return "+1666" + PHONE_SEQ.incrementAndGet();
    }

    protected String signInNewUser() throws Exception {
        return signIn(newPhone());
    }

    protected String signIn(String phone) throws Exception {
        mvc.perform(post("/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isAccepted());
        MvcResult result = mvc.perform(post("/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"000000\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    /** Signs in a new user, promotes it to ADMIN in the database and signs in again to get an ADMIN token. */
    protected String signInAdmin() throws Exception {
        String phone = newPhone();
        signIn(phone);
        jdbc.update("update app_user set role = 'ADMIN' where phone_e164 = ?", phone);
        return signIn(phone);
    }

    /** The user id is the JWT subject. */
    protected UUID userId(String token) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(payload).get("sub").asText());
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
