package com.example.ticketengine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Stand-in for a real email/SMS service reacting to reservation events. */
@Component
@ConditionalOnProperty(name = "ticket.async", havingValue = "true")
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    @KafkaListener(topics = Topics.EVENTS, groupId = "notification-service")
    public void onEvent(String payload) {
        log.info("[notification] would send a confirmation email for {}", payload);
    }
}
