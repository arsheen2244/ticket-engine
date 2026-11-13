package com.example.ticketengine;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ReservationService {

    /** reservation == null means "accepted and queued, not stored yet". */
    public record AsyncResult(Reservation reservation) {}

    private static final int USER_LIMIT = 4;
    private static final int MAX_OPTIMISTIC_ATTEMPTS = 100;
    private static final int IDEMPOTENCY_TTL_SECONDS = 600;

    private final InventoryRepository repo;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;
    private final ObjectProvider<ReservationRequestProducer> producer;
    private final RedisScript<Long> reserveScript =
            RedisScript.of(new ClassPathResource("scripts/reserve.lua"), Long.class);

    public ReservationService(InventoryRepository repo, StringRedisTemplate redis, TransactionTemplate tx,
                              ObjectProvider<ReservationRequestProducer> producer) {
        this.repo = repo;
        this.redis = redis;
        this.tx = tx;
        this.producer = producer;
    }

    private static String stockKey(long eventId) { return "stock:" + eventId; }
    private static String userKey(long eventId, String userId) { return "user:" + eventId + ":" + userId; }
    private static String idemKey(long eventId, String key) { return "idem:" + eventId + ":" + key; }

    public long createEvent(String name, int total) {
        long id = repo.createEvent(name, total);
        redis.opsForValue().set(stockKey(id), String.valueOf(total));
        return id;
    }

    /** Synchronous strategies. REDIS_ASYNC goes through {@link #reserveAsync}. */
    public Reservation reserve(long eventId, String userId, int qty, String key, Strategy strategy) {
        if (strategy == Strategy.REDIS_ASYNC) {
            throw new IllegalArgumentException("Use reserveAsync for REDIS_ASYNC");
        }
        // The DB strategies dedupe with a lookup. The Redis strategy dedupes inside the Lua script,
        // so rejected requests never touch Postgres at all.
        if (strategy != Strategy.REDIS) {
            var existing = repo.findByKey(key);
            if (existing.isPresent()) return existing.get();
        }
        try {
            return switch (strategy) {
                case NAIVE -> naive(eventId, userId, qty, key);
                case PESSIMISTIC -> pessimistic(eventId, userId, qty, key);
                case OPTIMISTIC -> optimistic(eventId, userId, qty, key);
                case REDIS -> redisGated(eventId, userId, qty, key);
                case REDIS_ASYNC -> throw new IllegalStateException("unreachable");
            };
        } catch (DuplicateKeyException dup) {
            return repo.findByKey(key).orElseThrow();
        }
    }

    private Reservation naive(long eventId, String userId, int qty, String key) {
        int available = repo.available(eventId);
        if (available < qty) throw new SoldOutException();
        repo.setAvailable(eventId, available - qty);
        return repo.insert(eventId, userId, qty, key);
    }

    private Reservation pessimistic(long eventId, String userId, int qty, String key) {
        return tx.execute(status -> {
            int available = repo.lockAvailable(eventId);
            if (available < qty) throw new SoldOutException();
            repo.setAvailable(eventId, available - qty);
            return repo.insert(eventId, userId, qty, key);
        });
    }

    private Reservation optimistic(long eventId, String userId, int qty, String key) {
        for (int attempt = 0; attempt < MAX_OPTIMISTIC_ATTEMPTS; attempt++) {
            Reservation r = tx.execute(status -> {
                var inv = repo.availableAndVersion(eventId);
                if (inv.available() < qty) throw new SoldOutException();
                if (!repo.updateWithVersion(eventId, qty, inv.version())) return null;
                return repo.insert(eventId, userId, qty, key);
            });
            if (r != null) return r;
            try { Thread.sleep(ThreadLocalRandom.current().nextInt(1, 6)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Too much contention, retry");
    }

    /** One atomic Lua call: duplicate? sold out? user limit? then decrement and remember the key. */
    private long runGate(long eventId, String userId, int qty, String key) {
        Long result = redis.execute(reserveScript,
                List.of(stockKey(eventId), userKey(eventId, userId), idemKey(eventId, key)),
                String.valueOf(qty), String.valueOf(USER_LIMIT), String.valueOf(IDEMPOTENCY_TTL_SECONDS));
        return result == null ? -1 : result;
    }

    private Reservation redisGated(long eventId, String userId, int qty, String key) {
        long result = runGate(eventId, userId, qty, key);
        if (result == -1) throw new SoldOutException();
        if (result == -2) throw new LimitExceededException();
        if (result == -3) {
            return repo.findByKey(key).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate request still in progress"));
        }
        try {
            return tx.execute(status -> {
                if (!repo.decrementIfAvailable(eventId, qty)) throw new SoldOutException();
                return repo.insert(eventId, userId, qty, key);
            });
        } catch (RuntimeException e) {
            releaseRedis(eventId, userId, qty, key);
            throw e;
        }
    }

    /**
     * Same Redis gate, but the Postgres write happens later: the request is published to Kafka and a
     * consumer stores it. If publishing fails, the Redis stock is given back and the client gets 503.
     */
    public AsyncResult reserveAsync(long eventId, String userId, int qty, String key) {
        ReservationRequestProducer kafkaProducer = producer.getIfAvailable();
        if (kafkaProducer == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Async mode is off. Start the app with TICKET_ASYNC=true and a Kafka broker.");
        }
        long result = runGate(eventId, userId, qty, key);
        if (result == -1) throw new SoldOutException();
        if (result == -2) throw new LimitExceededException();
        if (result == -3) return new AsyncResult(repo.findByKey(key).orElse(null));
        try {
            kafkaProducer.send(new ReservationRequest(eventId, userId, qty, key));
        } catch (RuntimeException e) {
            releaseRedis(eventId, userId, qty, key);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Queue unavailable, please retry");
        }
        return new AsyncResult(null);
    }

    // ---- Redis helpers used by compensation, expiry and reconciliation ----
    public void restoreStock(long eventId, String userId, int qty) {
        redis.opsForValue().increment(stockKey(eventId), qty);
        redis.opsForValue().decrement(userKey(eventId, userId), qty);
    }

    public void releaseRedis(long eventId, String userId, int qty, String key) {
        restoreStock(eventId, userId, qty);
        redis.delete(idemKey(eventId, key));
    }

    public Long redisStock(long eventId) {
        String v = redis.opsForValue().get(stockKey(eventId));
        return v == null ? null : Long.valueOf(v);
    }

    public void setRedisStock(long eventId, long value) {
        redis.opsForValue().set(stockKey(eventId), String.valueOf(value));
    }

    /**
     * True while the Redis gate remembers this key as accepted. For an async booking with no Postgres row
     * yet, true means "still queued"; false means it was released (rejected) or never accepted.
     */
    public boolean isAcceptedInRedis(long eventId, String key) {
        return Boolean.TRUE.equals(redis.hasKey(idemKey(eventId, key)));
    }

    /** Current Redis stock counters for several events in one round trip; missing counters are left out. */
    public Map<Long, Long> redisStocks(List<Long> eventIds) {
        List<String> values = redis.opsForValue().multiGet(eventIds.stream().map(ReservationService::stockKey).toList());
        Map<Long, Long> out = new HashMap<>();
        for (int i = 0; values != null && i < eventIds.size(); i++) {
            if (values.get(i) != null) out.put(eventIds.get(i), Long.valueOf(values.get(i)));
        }
        return out;
    }

    /** True when the app runs with TICKET_ASYNC=true, so the redis_async strategy is available. */
    public boolean asyncEnabled() { return producer.getIfAvailable() != null; }

    public Map<String, Object> stats(long eventId) {
        int total = repo.totalTickets(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        Map<String, Object> out = new HashMap<>(repo.reservationTotals(eventId));
        long reserved = ((Number) out.get("tickets_reserved")).longValue();
        out.put("total_tickets", total);
        out.put("db_available", repo.available(eventId));
        out.put("redis_stock", redisStock(eventId));
        out.put("oversold", reserved > total);
        return out;
    }

    public void reset(long eventId) {
        int total = repo.totalTickets(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        repo.reset(eventId);
        setRedisStock(eventId, total);
        for (String pattern : List.of("user:" + eventId + ":*", "idem:" + eventId + ":*")) {
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        }
    }
}
