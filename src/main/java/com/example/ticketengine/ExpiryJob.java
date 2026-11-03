package com.example.ticketengine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Releases tickets held by reservations that were never paid (expires_at has passed). */
@Component
@ConditionalOnProperty(name = "ticket.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);

    private final LifecycleRepository lifecycle;
    private final ReservationService service;
    private final TransactionTemplate tx;

    public ExpiryJob(LifecycleRepository lifecycle, ReservationService service, TransactionTemplate tx) {
        this.lifecycle = lifecycle;
        this.service = service;
        this.tx = tx;
    }

    @Scheduled(initialDelay = 30000, fixedDelayString = "${ticket.expiry.delay-ms:30000}")
    public void expire() {
        try {
            List<LifecycleRepository.Expired> expired = tx.execute(status -> {
                var list = lifecycle.expirePending(500);
                for (var e : list) lifecycle.restoreInventory(e.eventId(), e.qty());
                return list;
            });
            if (expired == null || expired.isEmpty()) return;
            // Redis is updated after the DB commit. If we crash in between, the reconciliation job repairs it.
            for (var e : expired) service.restoreStock(e.eventId(), e.userId(), e.qty());
            log.info("Expired {} unpaid reservations and returned their tickets", expired.size());
        } catch (RuntimeException e) {
            log.warn("Expiry run failed, will retry: {}", e.getMessage());
        }
    }
}
