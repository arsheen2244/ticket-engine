package com.example.ticketengine;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
public class EventController {

    public record CreateEventRequest(@NotBlank String name, @Min(1) int totalTickets) {}
    public record ReserveRequest(@NotBlank String userId, @Min(1) int qty) {}

    private final ReservationService service;

    public EventController(ReservationService service) { this.service = service; }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody CreateEventRequest req) {
        return Map.of("id", service.createEvent(req.name(), req.totalTickets()));
    }

    /** strategy = naive | pessimistic | optimistic | redis (default) | redis_async. */
    @PostMapping("/{id}/reserve")
    public ResponseEntity<?> reserve(
            @PathVariable long id,
            @Valid @RequestBody ReserveRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestParam(defaultValue = "redis") String strategy) {
        String idemKey = (key == null || key.isBlank()) ? UUID.randomUUID().toString() : key;
        // "acct-" ids and keys belong to signed-in accounts (POST /api/bookings); this public endpoint may not use them.
        if (req.userId().startsWith("acct-") || idemKey.startsWith("acct-")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId and Idempotency-Key may not start with acct-");
        }
        Strategy s;
        try {
            s = Strategy.valueOf(strategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown strategy: " + strategy);
        }
        if (s == Strategy.REDIS_ASYNC) {
            var result = service.reserveAsync(id, req.userId(), req.qty(), idemKey);
            if (result.reservation() != null) {
                return ResponseEntity.status(HttpStatus.CREATED).body(result.reservation());
            }
            // 202: accepted and queued. Poll GET /api/reservations/by-key/{key} for the outcome.
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "QUEUED", "idempotencyKey", idemKey));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.reserve(id, req.userId(), req.qty(), idemKey, s));
    }

    @GetMapping("/{id}/stats")
    public Map<String, Object> stats(@PathVariable long id) { return service.stats(id); }

    @PostMapping("/{id}/reset")
    public Map<String, String> reset(@PathVariable long id) {
        service.reset(id);
        return Map.of("status", "reset");
    }
}
