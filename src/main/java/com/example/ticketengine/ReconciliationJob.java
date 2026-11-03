package com.example.ticketengine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Postgres is the source of truth. If the Redis stock counter disagrees with it for two runs in a
 * row (so it is not just a request in flight), Redis is overwritten. This also recovers from a Redis restart.
 */
@Component
@ConditionalOnProperty(name = "ticket.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private static final long MISSING = Long.MIN_VALUE;

    private final LifecycleRepository lifecycle;
    private final InventoryRepository inventory;
    private final ReservationService service;
    private final Map<Long, Long> lastDrift = new ConcurrentHashMap<>();

    public ReconciliationJob(LifecycleRepository lifecycle, InventoryRepository inventory, ReservationService service) {
        this.lifecycle = lifecycle;
        this.inventory = inventory;
        this.service = service;
    }

    @Scheduled(initialDelay = 45000, fixedDelayString = "${ticket.reconcile.delay-ms:60000}")
    public void reconcile() {
        try {
            for (long eventId : lifecycle.allEventIds()) {
                long db = inventory.available(eventId);
                Long redis = service.redisStock(eventId);
                long drift = redis == null ? MISSING : redis - db;
                if (redis != null && drift == 0) {
                    lastDrift.remove(eventId);
                    continue;
                }
                Long previous = lastDrift.put(eventId, drift);
                if (previous != null && previous == drift) {
                    service.setRedisStock(eventId, db);
                    lastDrift.remove(eventId);
                    log.warn("Reconciled event {}: Redis stock was {}, reset to Postgres value {}",
                            eventId, redis == null ? "missing" : redis, db);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Reconciliation run failed, will retry: {}", e.getMessage());
        }
    }
}
