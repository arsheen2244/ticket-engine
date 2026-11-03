package com.example.ticketengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "ticket.async", havingValue = "true")
public class ReservationRequestProducer {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public ReservationRequestProducer(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    /** Blocks until the broker acknowledges (acks=all), so a 202 means the request is durably queued. */
    public void send(ReservationRequest request) {
        try {
            // Keyed by event id: all requests for one event go to one partition, in order.
            kafka.send(Topics.REQUESTS, String.valueOf(request.eventId()), mapper.writeValueAsString(request))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka publish failed", e);
        }
    }
}
