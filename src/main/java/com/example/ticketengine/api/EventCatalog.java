package com.example.ticketengine.api;

import com.example.ticketengine.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Builds {@link EventView}s. The remaining count comes from Redis, which every booking passes first. */
@Service
public class EventCatalog {

    public static final int LIST_LIMIT = 50;

    private final WebAppRepository repo;
    private final ReservationService reservations;

    public EventCatalog(WebAppRepository repo, ReservationService reservations) {
        this.repo = repo;
        this.reservations = reservations;
    }

    public List<EventView> recent() {
        return repo.recentEvents(LIST_LIMIT).stream().map(this::view).toList();
    }

    public EventView get(long id) {
        return repo.event(id).map(this::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    /** Live remaining count: Redis when it has the counter, Postgres otherwise. Never below 0. */
    public long remaining(long eventId, int dbAvailable) {
        Long redis = reservations.redisStock(eventId);
        return Math.max(0, redis != null ? redis : dbAvailable);
    }

    private EventView view(WebAppRepository.EventRow row) {
        long remaining = remaining(row.id(), row.dbAvailable());
        return new EventView(row.id(), row.name(), row.totalTickets(), remaining, remaining == 0, row.createdAt());
    }
}
