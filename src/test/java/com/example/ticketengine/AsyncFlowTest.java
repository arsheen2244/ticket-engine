package com.example.ticketengine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the whole async pipeline against an in-JVM Kafka broker, so it needs no Kafka install.
 * Postgres and Redis must still be running.
 */
@SpringBootTest(properties = "ticket.async=true")
@EmbeddedKafka(partitions = 3, topics = {Topics.REQUESTS, Topics.EVENTS},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class AsyncFlowTest {

    @Autowired
    ReservationService service;

    @Autowired
    LifecycleRepository lifecycle;

    @Test
    void async_pipeline_stores_exactly_the_stock_and_drains_the_outbox() throws Exception {
        int tickets = 20;
        long eventId = service.createEvent("async-flow", tickets);
        AtomicInteger accepted = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.reserveAsync(eventId, "user-" + n, 1, UUID.randomUUID().toString());
                    accepted.incrementAndGet();
                } catch (SoldOutException ignored) {
                    // expected for the 180 requests that lose at the Redis gate
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        // The Redis gate lets exactly `tickets` requests through.
        assertThat(accepted.get()).isEqualTo(tickets);

        // The consumer eventually writes all of them to Postgres, without overselling.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var stats = service.stats(eventId);
            assertThat(((Number) stats.get("tickets_reserved")).longValue()).isEqualTo(tickets);
            assertThat(((Number) stats.get("db_available")).longValue()).isZero();
            assertThat(stats.get("oversold")).isEqualTo(false);
        });

        // The outbox publisher relays every RESERVATION_CREATED event to Kafka.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(lifecycle.countUnpublished()).isZero());
    }
}
