package com.example.ticketengine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "ticket.async", havingValue = "true")
public class ReservationWriter {

    private static final Logger log = LoggerFactory.getLogger(ReservationWriter.class);

    private final InventoryRepository repo;
    private final LifecycleRepository lifecycle;
    private final ReservationService service;
    private final ObjectMapper mapper;

    public ReservationWriter(InventoryRepository repo, LifecycleRepository lifecycle,
                             ReservationService service, ObjectMapper mapper) {
        this.repo = repo;
        this.lifecycle = lifecycle;
        this.service = service;
        this.mapper = mapper;
    }

    /**
     * Idempotent consumer. In ONE transaction: decrement stock, insert the reservation, and insert the
     * outbox row. Either all three happen or none do (the transactional outbox pattern).
     */
    @Transactional
    public void persist(ReservationRequest r) throws JsonProcessingException {
        if (repo.findByKey(r.idempotencyKey()).isPresent()) {
            return; // redelivered message: already stored
        }
        if (!repo.decrementIfAvailable(r.eventId(), r.qty())) {
            // Redis said yes but Postgres has no stock: drift. Give the Redis stock back and drop it.
            log.warn("Rejected {} for event {}: Postgres has no stock (Redis/DB drift)", r.idempotencyKey(), r.eventId());
            service.releaseRedis(r.eventId(), r.userId(), r.qty(), r.idempotencyKey());
            return;
        }
        Reservation reservation = repo.insert(r.eventId(), r.userId(), r.qty(), r.idempotencyKey());
        lifecycle.insertOutbox(reservation.id().toString(), "RESERVATION_CREATED",
                mapper.writeValueAsString(reservation));
    }
}
