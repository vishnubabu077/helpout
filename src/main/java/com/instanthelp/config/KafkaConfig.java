package com.instanthelp.config;

import com.instanthelp.outbox.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableScheduling
public class KafkaConfig {

    @Bean
    NewTopic helperAvailabilityTopic() {
        return TopicBuilder.name(KafkaTopics.HELPER_AVAILABILITY).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic taskEventsTopic() {
        return TopicBuilder.name(KafkaTopics.TASK_EVENTS).partitions(3).replicas(1).build();
    }

    /**
     * Redis being down must not make the projector skip events: retry the same record every second forever.
     * (Unparseable messages never reach this handler, the listener drops them itself.)
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
