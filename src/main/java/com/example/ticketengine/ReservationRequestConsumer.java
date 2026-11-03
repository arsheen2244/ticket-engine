package com.example.ticketengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ticket.async", havingValue = "true")
public class ReservationRequestConsumer {

    private final ReservationWriter writer;
    private final ObjectMapper mapper;

    public ReservationRequestConsumer(ReservationWriter writer, ObjectMapper mapper) {
        this.writer = writer;
        this.mapper = mapper;
    }

    /**
     * At-least-once: the offset is committed only after this method returns. A malformed or failing
     * message is retried by the error handler and finally parked on the dead-letter topic.
     */
    @KafkaListener(topics = Topics.REQUESTS, groupId = "reservation-writer")
    public void onMessage(String payload) throws Exception {
        writer.persist(mapper.readValue(payload, ReservationRequest.class));
    }
}
