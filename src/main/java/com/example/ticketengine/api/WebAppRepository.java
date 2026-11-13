package com.example.ticketengine.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read queries for the web app (event list, my orders, admin stats) and the owner-checked payment. */
@Repository
public class WebAppRepository {

    public record EventRow(long id, String name, int totalTickets, int dbAvailable, java.time.Instant createdAt) {}

    public record SalesRow(long id, String name, int totalTickets, int dbAvailable, long reservations,
                           long ticketsPending, long ticketsConfirmed, long ticketsExpired) {}

    private static final RowMapper<EventRow> EVENT = (rs, i) -> new EventRow(rs.getLong("id"), rs.getString("name"),
            rs.getInt("total_tickets"), rs.getInt("available"), rs.getTimestamp("created_at").toInstant());

    private static final String ORDER_COLUMNS = """
            SELECT r.id, r.event_id, e.name, r.qty, r.status, r.idempotency_key, r.created_at, r.expires_at
            FROM reservations r JOIN events e ON e.id = r.event_id""";

    private final JdbcTemplate jdbc;

    public WebAppRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<EventRow> recentEvents(int limit) {
        return jdbc.query("""
                SELECT e.id, e.name, e.total_tickets, i.available, e.created_at
                FROM events e JOIN inventory i ON i.event_id = e.id
                ORDER BY e.id DESC LIMIT ?""", EVENT, limit);
    }

    public Optional<EventRow> event(long id) {
        return jdbc.query("""
                SELECT e.id, e.name, e.total_tickets, i.available, e.created_at
                FROM events e JOIN inventory i ON i.event_id = e.id WHERE e.id = ?""", EVENT, id)
                .stream().findFirst();
    }

    /** {@code keyPrefix} is "acct-&lt;id&gt;:" and is stripped so the client sees its own key. */
    public List<OrderView> ordersOf(String userId, String keyPrefix, int limit) {
        return jdbc.query(ORDER_COLUMNS + " WHERE r.user_id = ? ORDER BY r.created_at DESC LIMIT ?",
                orderMapper(keyPrefix), userId, limit);
    }

    public Optional<OrderView> orderByKey(String userId, String keyPrefix, String clientKey) {
        return jdbc.query(ORDER_COLUMNS + " WHERE r.user_id = ? AND r.idempotency_key = ?",
                orderMapper(keyPrefix), userId, keyPrefix + clientKey).stream().findFirst();
    }

    public Optional<OrderView> orderById(String userId, String keyPrefix, UUID id) {
        return jdbc.query(ORDER_COLUMNS + " WHERE r.user_id = ? AND r.id = ?",
                orderMapper(keyPrefix), userId, id).stream().findFirst();
    }

    /** Mock payment, but only for the caller's own pending, unexpired reservation. */
    public boolean payOwn(UUID id, String userId) {
        return jdbc.update("""
                UPDATE reservations SET status = 'CONFIRMED'
                WHERE id = ? AND user_id = ? AND status = 'PENDING_PAYMENT' AND expires_at > now()""",
                id, userId) == 1;
    }

    public List<SalesRow> salesStats(int limit) {
        return jdbc.query("""
                SELECT e.id, e.name, e.total_tickets, i.available,
                       COUNT(r.id) AS reservations,
                       COALESCE(SUM(r.qty) FILTER (WHERE r.status = 'PENDING_PAYMENT'), 0) AS pending,
                       COALESCE(SUM(r.qty) FILTER (WHERE r.status = 'CONFIRMED'), 0)       AS confirmed,
                       COALESCE(SUM(r.qty) FILTER (WHERE r.status = 'EXPIRED'), 0)         AS expired
                FROM events e
                JOIN inventory i ON i.event_id = e.id
                LEFT JOIN reservations r ON r.event_id = e.id
                GROUP BY e.id, e.name, e.total_tickets, i.available
                ORDER BY e.id DESC LIMIT ?""",
                (rs, i) -> new SalesRow(rs.getLong("id"), rs.getString("name"), rs.getInt("total_tickets"),
                        rs.getInt("available"), rs.getLong("reservations"), rs.getLong("pending"),
                        rs.getLong("confirmed"), rs.getLong("expired")), limit);
    }

    public Map<String, Object> totals() {
        return jdbc.queryForMap("""
                SELECT (SELECT COUNT(*) FROM events) AS events,
                       (SELECT COUNT(*) FROM users) AS users,
                       (SELECT COALESCE(SUM(qty), 0) FROM reservations WHERE status = 'CONFIRMED') AS tickets_confirmed""");
    }

    private static RowMapper<OrderView> orderMapper(String keyPrefix) {
        return (rs, i) -> {
            String key = rs.getString("idempotency_key");
            return new OrderView(rs.getObject("id", UUID.class), rs.getLong("event_id"), rs.getString("name"),
                    rs.getInt("qty"), rs.getString("status"),
                    key.startsWith(keyPrefix) ? key.substring(keyPrefix.length()) : key,
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant());
        };
    }
}
