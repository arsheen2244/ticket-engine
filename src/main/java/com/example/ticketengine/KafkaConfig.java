package com.example.ticketengine;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "ticket.async", havingValue = "true")
public class KafkaConfig {

    @Bean
    NewTopic requestsTopic() {
        return TopicBuilder.name(Topics.REQUESTS).partitions(3).replicas(1).build();
    }

    /** The dead-letter topic needs the same partition count as the source topic. */
    @Bean
    NewTopic requestsDeadLetterTopic() {
        return TopicBuilder.name(Topics.REQUESTS + ".DLT").partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic eventsTopic() {
        return TopicBuilder.name(Topics.EVENTS).partitions(3).replicas(1).build();
    }

    /** Retry a failing message 3 times, 500 ms apart, then park it on reservation-requests.DLT. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(500L, 3L));
    }
}
