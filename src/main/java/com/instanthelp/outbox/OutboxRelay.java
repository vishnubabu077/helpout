package com.instanthelp.outbox;

import com.instanthelp.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka. Rows are claimed with FOR UPDATE SKIP LOCKED, so any number of app
 * instances can run this at the same time without publishing the same row twice (a crash between the Kafka ack
 * and the commit can still repeat a row: delivery is at-least-once, consumers dedupe by version).
 * The message key is the row's partition_key, which keeps every event of one helper on one partition.
 */
@Component
public class OutboxRelay implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       AppProperties props) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::drain, props.outbox().pollInterval());
    }

    /** Publishes full batches back to back until the outbox is (nearly) empty. Never throws. */
    void drain() {
        try {
            int published;
            do {
                published = publishBatch();
            } while (published >= props.outbox().batchSize());
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed, will retry: {}", e.toString());
        }
    }

    /** One batch in one transaction: claim, publish, wait for the broker ack, mark published. */
    public int publishBatch() {
        Integer count = tx.execute(status -> {
            List<OutboxRow> rows = jdbc.sql("""
                            select id, aggregate_type, partition_key, event_type, payload::text as payload
                              from outbox_event
                             where published_at is null
                             order by created_at, id
                             limit :limit
                               for update skip locked""")
                    .param("limit", props.outbox().batchSize())
                    .query((rs, i) -> new OutboxRow(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
                            rs.getString("partition_key"), rs.getString("event_type"), rs.getString("payload")))
                    .list();
            if (rows.isEmpty()) {
                return 0;
            }

            List<UUID> acked = new ArrayList<>();
            List<Future<SendResult<String, String>>> sends = new ArrayList<>();
            List<OutboxRow> sent = new ArrayList<>();
            for (OutboxRow row : rows) {
                String topic = KafkaTopics.BY_AGGREGATE.get(row.aggregateType());
                if (topic == null) {
                    log.error("Outbox row {} has unknown aggregate type {}, leaving it unpublished", row.id(), row.aggregateType());
                    continue;
                }
                try {
                    var record = new ProducerRecord<>(topic, row.partitionKey(), row.payload());
                    record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
                    sends.add(kafka.send(record));
                    sent.add(row);
                } catch (RuntimeException e) {
                    log.warn("Kafka send failed for outbox row {}: {}", row.id(), e.toString());
                    break; // broker unreachable: do not wait max.block.ms for every remaining row
                }
            }
            for (int i = 0; i < sends.size(); i++) {
                try {
                    sends.get(i).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    acked.add(sent.get(i).id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("No Kafka ack for outbox row {}: {}", sent.get(i).id(), e.toString());
                }
            }
            if (!acked.isEmpty()) {
                jdbc.sql("update outbox_event set published_at = now() where id in (:ids)")
                        .param("ids", acked).update();
            }
            return rows.size();
        });
        return count == null ? 0 : count;
    }

    private record OutboxRow(UUID id, String aggregateType, String partitionKey, String eventType, String payload) {}
}
