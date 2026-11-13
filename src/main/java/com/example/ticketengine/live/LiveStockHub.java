package com.example.ticketengine.live;

import com.example.ticketengine.ReservationService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pushes live "tickets remaining" counts to browsers over Server-Sent Events.
 *
 * <p>Every booking passes the Redis Lua gate, which keeps the stock counter {@code stock:<eventId>}.
 * Every {@code ticket.live.interval-ms} this hub reads the counters of the events that have viewers
 * (one MGET) and sends an update only when a count changed. Because it reads Redis rather than reacting
 * to requests, it is naturally rate-limited during a rush, sees changes from every path (bookings,
 * expiry, reconciliation, admin reset) and works the same with many app instances.
 */
@Component
public class LiveStockHub {

    public record StockUpdate(long eventId, long remaining, boolean soldOut) {}

    private static final Logger log = LoggerFactory.getLogger(LiveStockHub.class);
    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30); // EventSource reconnects by itself
    static final long HEARTBEAT_EVERY_MS = 15_000;

    private final ReservationService reservations;
    private final int maxConnections;
    private final Map<Long, Set<SseEmitter>> viewers = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastSent = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    // Own thread, so slow clients never delay the outbox, expiry or reconciliation jobs.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-stock");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastHeartbeat = System.currentTimeMillis();

    public LiveStockHub(ReservationService reservations,
                        @Value("${ticket.live.interval-ms:250}") long intervalMs,
                        @Value("${ticket.live.max-connections:5000}") int maxConnections) {
        this.reservations = reservations;
        this.maxConnections = maxConnections;
        scheduler.scheduleWithFixedDelay(this::tickSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Opens a stream for one event and sends its current count straight away. */
    public SseEmitter subscribe(long eventId, long currentRemaining) {
        if (connections.incrementAndGet() > maxConnections) {
            connections.decrementAndGet();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Too many live viewers, try again soon");
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        viewers.computeIfAbsent(eventId, id -> new CopyOnWriteArraySet<>()).add(emitter);
        Runnable remove = () -> remove(eventId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        send(eventId, emitter, new StockUpdate(eventId, currentRemaining, currentRemaining == 0));
        return emitter;
    }

    public int connectionCount() { return connections.get(); }

    private void remove(long eventId, SseEmitter emitter) {
        Set<SseEmitter> set = viewers.get(eventId);
        if (set != null && set.remove(emitter)) {
            connections.decrementAndGet();
            if (set.isEmpty()) {
                viewers.remove(eventId, set);
                lastSent.remove(eventId);
            }
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("Live stock update failed, will retry: {}", e.getMessage());
        }
    }

    /** One polling round: read watched counters, push the ones that changed, heartbeat now and then. */
    void tick() {
        if (viewers.isEmpty()) return;
        var ids = new ArrayList<>(viewers.keySet());
        Map<Long, Long> stocks = reservations.redisStocks(ids);
        for (long id : ids) {
            Long stock = stocks.get(id);
            if (stock == null) continue;
            long remaining = Math.max(0, stock);
            Long previous = lastSent.put(id, remaining);
            if (previous == null || previous != remaining) broadcast(id, new StockUpdate(id, remaining, remaining == 0));
        }
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat >= HEARTBEAT_EVERY_MS) {
            lastHeartbeat = now;
            viewers.forEach((id, set) -> set.forEach(e -> heartbeat(id, e)));
        }
    }

    private void broadcast(long eventId, StockUpdate update) {
        Set<SseEmitter> set = viewers.get(eventId);
        if (set != null) set.forEach(e -> send(eventId, e, update));
    }

    private void send(long eventId, SseEmitter emitter, StockUpdate update) {
        try {
            emitter.send(SseEmitter.event().name("stock").data(update));
        } catch (IOException | IllegalStateException e) {
            remove(eventId, emitter); // the browser went away
        }
    }

    /** A comment line keeps proxies from closing an idle connection. */
    private void heartbeat(long eventId, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException | IllegalStateException e) {
            remove(eventId, emitter);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        viewers.values().forEach(set -> set.forEach(SseEmitter::complete));
    }
}
