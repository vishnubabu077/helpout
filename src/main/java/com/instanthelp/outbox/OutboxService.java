package com.instanthelp.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox writer. Callers never talk to Kafka: they append a row here inside the same
 * transaction as their business change, and {@link OutboxRelay} publishes it afterwards.
 */
@Service
public class OutboxService {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public OutboxService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** MANDATORY: an outbox row without a surrounding business transaction would be a dual write. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, UUID aggregateId, String eventType, String partitionKey, Object payload) {
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize outbox payload for " + eventType, e);
        }
        jdbc.sql("""
                        insert into outbox_event (id, aggregate_type, aggregate_id, event_type, partition_key, payload)
                        values (:id, :aggregateType, :aggregateId, :eventType, :partitionKey, cast(:payload as jsonb))""")
                .param("id", UUID.randomUUID())
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("partitionKey", partitionKey)
                .param("payload", body)
                .update();
    }
}
