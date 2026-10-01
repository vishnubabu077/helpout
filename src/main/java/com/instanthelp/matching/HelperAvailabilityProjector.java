package com.instanthelp.matching;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instanthelp.helper.HelperAvailabilityEvent;
import com.instanthelp.outbox.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group "helper-geo-projector": keeps the Redis candidate index in step with helper availability.
 * Events are keyed by helperId (one partition per helper) and versioned, so applying them is idempotent.
 */
@Component
public class HelperAvailabilityProjector {

    private static final Logger log = LoggerFactory.getLogger(HelperAvailabilityProjector.class);

    private final HelperGeoIndex index;
    private final ObjectMapper json;

    public HelperAvailabilityProjector(HelperGeoIndex index, ObjectMapper json) {
        this.index = index;
        this.json = json;
    }

    @KafkaListener(topics = KafkaTopics.HELPER_AVAILABILITY, groupId = "helper-geo-projector")
    void onEvent(String payload) {
        HelperAvailabilityEvent event;
        try {
            event = json.readValue(payload, HelperAvailabilityEvent.class);
        } catch (JsonProcessingException e) {
            log.error("Dropping unparseable helper-availability message: {}", e.getOriginalMessage());
            return; // a poison message must not block the partition; a Redis failure below is retried instead
        }
        boolean applied = index.apply(event);
        log.debug("helper {} availability {} v{} {} -> {}", event.helperId(), event.availabilityId(),
                event.version(), event.status(), applied ? "applied" : "ignored (stale or duplicate)");
    }
}
