package com.example.ticketengine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Relays outbox rows to Kafka. Rows are marked published only after the broker acknowledges them.
 * A crash between "sent" and "marked" means a row is sent again: delivery is at-least-once,
 * so consumers of reservation-events must be idempotent.
 */
@Component
@ConditionalOnProperty(name = "ticket.async", havingValue = "true")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final LifecycleRepository lifecycle;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    public OutboxPublisher(LifecycleRepository lifecycle, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
        this.lifecycle = lifecycle;
        this.kafka = kafka;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${ticket.outbox.delay-ms:500}")
    public void publish() {
        try {
            tx.executeWithoutResult(status -> {
                // FOR UPDATE SKIP LOCKED lets several app instances share the outbox safely.
                for (var row : lifecycle.lockUnpublished(100)) {
                    try {
                        kafka.send(Topics.EVENTS, row.aggregateId(), row.payload()).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException("Outbox publish failed", e);
                    }
                    lifecycle.markPublished(row.id());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Outbox publish round failed, will retry: {}", e.getMessage());
        }
    }
}
