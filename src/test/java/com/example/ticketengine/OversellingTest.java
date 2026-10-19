package com.example.ticketengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Needs Postgres and Redis running (same ones the app uses). Each test creates its own event,
 * so it is safe to run while the app is up.
 */
@SpringBootTest
class OversellingTest {

    @Autowired
    ReservationService service;

    /** Releases all requests at the same instant to maximise contention. */
    private static void blast(int requests, int threads, IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                task.accept(n);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(90, TimeUnit.SECONDS);
        pool.shutdown();
    }

    private static long num(Map<String, Object> stats, String key) {
        return ((Number) stats.get(key)).longValue();
    }

    @ParameterizedTest
    @EnumSource(value = Strategy.class, names = {"PESSIMISTIC", "OPTIMISTIC", "REDIS"})
    void never_sells_more_than_stock(Strategy strategy) throws Exception {
        int tickets = 100;
        long eventId = service.createEvent("oversell-" + strategy, tickets);
        AtomicInteger sold = new AtomicInteger();

        blast(500, 64, n -> {
            try {
                service.reserve(eventId, "user-" + n, 1, UUID.randomUUID().toString(), strategy);
                sold.incrementAndGet();
            } catch (SoldOutException | ResponseStatusException ignored) {
                // expected for the 400 requests that lose
            }
        });

        Map<String, Object> stats = service.stats(eventId);
        assertThat(sold.get()).isEqualTo(tickets);
        assertThat(num(stats, "tickets_reserved")).isEqualTo(tickets);
        assertThat(num(stats, "db_available")).isZero();
        assertThat(stats.get("oversold")).isEqualTo(false);
        if (strategy == Strategy.REDIS) assertThat(num(stats, "redis_stock")).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Strategy.class, names = {"PESSIMISTIC", "OPTIMISTIC", "REDIS"})
    void same_idempotency_key_creates_one_reservation(Strategy strategy) throws Exception {
        long eventId = service.createEvent("idem-" + strategy, 10);
        String key = UUID.randomUUID().toString();

        blast(50, 32, n -> {
            try {
                service.reserve(eventId, "same-user", 1, key, strategy);
            } catch (Exception ignored) {
                // duplicates in flight may be told to retry; only the final state matters
            }
        });

        Map<String, Object> stats = service.stats(eventId);
        assertThat(num(stats, "reservations")).isEqualTo(1);
        assertThat(num(stats, "tickets_reserved")).isEqualTo(1);
        assertThat(num(stats, "db_available")).isEqualTo(9);
    }

    @Test
    void redis_enforces_per_user_limit() {
        long eventId = service.createEvent("limit", 100);
        for (int i = 0; i < 4; i++) {
            service.reserve(eventId, "bob", 1, UUID.randomUUID().toString(), Strategy.REDIS);
        }
        assertThatThrownBy(() ->
                service.reserve(eventId, "bob", 1, UUID.randomUUID().toString(), Strategy.REDIS))
                .isInstanceOf(LimitExceededException.class);
    }
}
