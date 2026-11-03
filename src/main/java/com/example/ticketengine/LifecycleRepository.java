package com.example.ticketengine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class LifecycleRepository {

    public record Expired(long eventId, String userId, int qty) {}
    public record OutboxRow(long id, String aggregateId, String type, String payload) {}

    private final JdbcTemplate jdbc;

    public LifecycleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ---- outbox ----
    public void insertOutbox(String aggregateId, String type, String payload) {
        jdbc.update("INSERT INTO outbox_events(aggregate_id, type, payload) VALUES (?, ?, ?)",
                aggregateId, type, payload);
    }

    public List<OutboxRow> lockUnpublished(int limit) {
        return jdbc.query("""
                SELECT id, aggregate_id, type, payload FROM outbox_events
                WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""",
                (rs, i) -> new OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), limit);
    }

    public void markPublished(long id) {
        jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", id);
    }

    public int countUnpublished() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL", Integer.class);
        return n == null ? 0 : n;
    }

    // ---- payment ----
    /** Mock payment. Succeeds only for a still-pending, not-yet-expired reservation. */
    public boolean markPaid(UUID id) {
        return jdbc.update("""
                UPDATE reservations SET status = 'CONFIRMED'
                WHERE id = ? AND status = 'PENDING_PAYMENT' AND expires_at > now()""", id) == 1;
    }

    // ---- expiry ----
    public List<Expired> expirePending(int limit) {
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM reservations
                    WHERE status = 'PENDING_PAYMENT' AND expires_at < now()
                    ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                UPDATE reservations r SET status = 'EXPIRED' FROM due WHERE r.id = due.id
                RETURNING r.event_id, r.user_id, r.qty""",
                (rs, i) -> new Expired(rs.getLong(1), rs.getString(2), rs.getInt(3)), limit);
    }

    public void restoreInventory(long eventId, int qty) {
        jdbc.update("UPDATE inventory SET available = available + ?, version = version + 1 WHERE event_id = ?",
                qty, eventId);
    }

    public List<Long> allEventIds() {
        return jdbc.query("SELECT id FROM events", (rs, i) -> rs.getLong(1));
    }
}
