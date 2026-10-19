package com.example.ticketengine;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    /** strategy = naive | pessimistic | optimistic | redis (default). */
    @PostMapping("/{id}/reserve")
    public ResponseEntity<Reservation> reserve(
            @PathVariable long id,
            @Valid @RequestBody ReserveRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestParam(defaultValue = "redis") String strategy) {
        String idemKey = (key == null || key.isBlank()) ? UUID.randomUUID().toString() : key;
        Strategy s = Strategy.valueOf(strategy.toUpperCase());
        Reservation r = service.reserve(id, req.userId(), req.qty(), idemKey, s);
        return ResponseEntity.status(HttpStatus.CREATED).body(r);
    }

    @GetMapping("/{id}/stats")
    public Map<String, Object> stats(@PathVariable long id) { return service.stats(id); }

    /** Benchmark helper: wipes reservations and restores stock in Postgres and Redis. */
    @PostMapping("/{id}/reset")
    public Map<String, String> reset(@PathVariable long id) {
        service.reset(id);
        return Map.of("status", "reset");
    }
}
