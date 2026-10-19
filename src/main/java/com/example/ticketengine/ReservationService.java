package com.example.ticketengine;

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

    private static final int USER_LIMIT = 4;
    private static final int MAX_OPTIMISTIC_ATTEMPTS = 100;

    private final InventoryRepository repo;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;
    private final RedisScript<Long> reserveScript =
            RedisScript.of(new ClassPathResource("scripts/reserve.lua"), Long.class);

    public ReservationService(InventoryRepository repo, StringRedisTemplate redis, TransactionTemplate tx) {
        this.repo = repo;
        this.redis = redis;
        this.tx = tx;
    }

    private static String stockKey(long eventId) { return "stock:" + eventId; }
    private static String userKey(long eventId, String userId) { return "user:" + eventId + ":" + userId; }

    public long createEvent(String name, int total) {
        long id = repo.createEvent(name, total);
        redis.opsForValue().set(stockKey(id), String.valueOf(total));
        return id;
    }

    public Reservation reserve(long eventId, String userId, int qty, String key, Strategy strategy) {
        // Idempotency: a retried request returns the original reservation.
        var existing = repo.findByKey(key);
        if (existing.isPresent()) return existing.get();

        try {
            return switch (strategy) {
                case NAIVE -> naive(eventId, userId, qty, key);
                case PESSIMISTIC -> pessimistic(eventId, userId, qty, key);
                case OPTIMISTIC -> optimistic(eventId, userId, qty, key);
                case REDIS -> redisGated(eventId, userId, qty, key);
            };
        } catch (DuplicateKeyException dup) {
            // Two concurrent requests with the same idempotency key: the loser returns the winner's row.
            return repo.findByKey(key).orElseThrow();
        }
    }

    /** Read, check, write with no locking and no transaction. Oversells under concurrency. */
    private Reservation naive(long eventId, String userId, int qty, String key) {
        int available = repo.available(eventId);
        if (available < qty) throw new SoldOutException();
        repo.setAvailable(eventId, available - qty);
        return repo.insert(eventId, userId, qty, key);
    }

    /** SELECT ... FOR UPDATE: correct, but requests queue up on one row lock. */
    private Reservation pessimistic(long eventId, String userId, int qty, String key) {
        return tx.execute(status -> {
            int available = repo.lockAvailable(eventId);
            if (available < qty) throw new SoldOutException();
            repo.setAvailable(eventId, available - qty);
            return repo.insert(eventId, userId, qty, key);
        });
    }

    /** Version check-and-swap with retries: correct, but heavy retry storms under contention. */
    private Reservation optimistic(long eventId, String userId, int qty, String key) {
        for (int attempt = 0; attempt < MAX_OPTIMISTIC_ATTEMPTS; attempt++) {
            Reservation r = tx.execute(status -> {
                var inv = repo.availableAndVersion(eventId);
                if (inv.available() < qty) throw new SoldOutException();
                if (!repo.updateWithVersion(eventId, qty, inv.version())) return null; // lost the race
                return repo.insert(eventId, userId, qty, key);
            });
            if (r != null) return r;
            try { Thread.sleep(ThreadLocalRandom.current().nextInt(1, 6)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Too much contention, retry");
    }

    /**
     * Redis Lua script rejects excess traffic atomically and cheaply. Only winners touch Postgres,
     * and the DB conditional update + CHECK constraint remain as the final guard.
     * (Phase 4 moves the Postgres write behind Kafka.)
     */
    private Reservation redisGated(long eventId, String userId, int qty, String key) {
        Long result = redis.execute(reserveScript,
                List.of(stockKey(eventId), userKey(eventId, userId)),
                String.valueOf(qty), String.valueOf(USER_LIMIT));
        if (result == null || result == -1) throw new SoldOutException();
        if (result == -2) throw new LimitExceededException();

        try {
            return tx.execute(status -> {
                if (!repo.decrementIfAvailable(eventId, qty)) throw new SoldOutException(); // Redis/DB drift
                return repo.insert(eventId, userId, qty, key);
            });
        } catch (RuntimeException e) {
            // Compensate: give the stock back so Redis does not leak tickets.
            redis.opsForValue().increment(stockKey(eventId), qty);
            redis.opsForValue().decrement(userKey(eventId, userId), qty);
            throw e;
        }
    }

    public Map<String, Object> stats(long eventId) {
        int total = repo.totalTickets(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        Map<String, Object> out = new HashMap<>(repo.reservationTotals(eventId));
        long reserved = ((Number) out.get("tickets_reserved")).longValue();
        String redisStock = redis.opsForValue().get(stockKey(eventId));
        out.put("total_tickets", total);
        out.put("db_available", repo.available(eventId));
        out.put("redis_stock", redisStock == null ? null : Long.valueOf(redisStock));
        out.put("oversold", reserved > total);
        return out;
    }

    public void reset(long eventId) {
        int total = repo.totalTickets(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        repo.reset(eventId);
        redis.opsForValue().set(stockKey(eventId), String.valueOf(total));
        Set<String> userKeys = redis.keys("user:" + eventId + ":*");
        if (userKeys != null && !userKeys.isEmpty()) redis.delete(userKeys);
    }
}
