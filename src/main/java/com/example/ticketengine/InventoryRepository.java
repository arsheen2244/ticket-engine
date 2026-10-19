package com.example.ticketengine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InventoryRepository {

    public record InvState(int available, long version) {}

    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long createEvent(String name, int total) {
        Long id = jdbc.queryForObject(
                "INSERT INTO events(name, total_tickets) VALUES (?, ?) RETURNING id", Long.class, name, total);
        jdbc.update("INSERT INTO inventory(event_id, available) VALUES (?, ?)", id, total);
        return id;
    }

    public Optional<Integer> totalTickets(long eventId) {
        return jdbc.query("SELECT total_tickets FROM events WHERE id = ?",
                (rs, i) -> rs.getInt(1), eventId).stream().findFirst();
    }

    /** Plain read, no locking. Used by the NAIVE strategy (race condition on purpose). */
    public int available(long eventId) {
        Integer v = jdbc.queryForObject("SELECT available FROM inventory WHERE event_id = ?", Integer.class, eventId);
        return v == null ? 0 : v;
    }

    public void setAvailable(long eventId, int value) {
        jdbc.update("UPDATE inventory SET available = ? WHERE event_id = ?", value, eventId);
    }

    /** Row lock for PESSIMISTIC strategy. Must run inside a transaction. */
    public int lockAvailable(long eventId) {
        Integer v = jdbc.queryForObject(
                "SELECT available FROM inventory WHERE event_id = ? FOR UPDATE", Integer.class, eventId);
        return v == null ? 0 : v;
    }

    public InvState availableAndVersion(long eventId) {
        return jdbc.queryForObject("SELECT available, version FROM inventory WHERE event_id = ?",
                (rs, i) -> new InvState(rs.getInt(1), rs.getLong(2)), eventId);
    }

    /** Compare-and-swap on version for OPTIMISTIC strategy. */
    public boolean updateWithVersion(long eventId, int qty, long version) {
        return jdbc.update("""
                UPDATE inventory SET available = available - ?, version = version + 1
                WHERE event_id = ? AND version = ? AND available >= ?""",
                qty, eventId, version, qty) == 1;
    }

    /** Single atomic conditional decrement, used when Redis has already gated the request. */
    public boolean decrementIfAvailable(long eventId, int qty) {
        return jdbc.update("""
                UPDATE inventory SET available = available - ?, version = version + 1
                WHERE event_id = ? AND available >= ?""", qty, eventId, qty) == 1;
    }

    public Reservation insert(long eventId, String userId, int qty, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reservations(id, event_id, user_id, qty, status, idempotency_key, expires_at)
                VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', ?, now() + interval '5 minutes')""",
                id, eventId, userId, qty, key);
        return new Reservation(id, eventId, userId, qty, "PENDING_PAYMENT");
    }

    public Optional<Reservation> findByKey(String key) {
        return jdbc.query("""
                SELECT id, event_id, user_id, qty, status FROM reservations WHERE idempotency_key = ?""",
                (rs, i) -> new Reservation(rs.getObject(1, UUID.class), rs.getLong(2),
                        rs.getString(3), rs.getInt(4), rs.getString(5)), key).stream().findFirst();
    }

    public Map<String, Object> reservationTotals(long eventId) {
        return jdbc.queryForMap("""
                SELECT COUNT(*) AS reservations, COALESCE(SUM(qty), 0) AS tickets_reserved
                FROM reservations WHERE event_id = ?""", eventId);
    }

    public void reset(long eventId) {
        jdbc.update("DELETE FROM reservations WHERE event_id = ?", eventId);
        jdbc.update("""
                UPDATE inventory SET available = (SELECT total_tickets FROM events WHERE id = ?), version = 0
                WHERE event_id = ?""", eventId, eventId);
    }
}
