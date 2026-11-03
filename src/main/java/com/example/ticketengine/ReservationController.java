package com.example.ticketengine;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final InventoryRepository repo;
    private final LifecycleRepository lifecycle;

    public ReservationController(InventoryRepository repo, LifecycleRepository lifecycle) {
        this.repo = repo;
        this.lifecycle = lifecycle;
    }

    /** Status lookup for async reservations: 404 means "not stored yet" (or rejected). */
    @GetMapping("/by-key/{key}")
    public ResponseEntity<Reservation> byKey(@PathVariable String key) {
        return repo.findByKey(key).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Mock payment. Only a pending, unexpired reservation can be confirmed. */
    @PostMapping("/{id}/pay")
    public ResponseEntity<Map<String, String>> pay(@PathVariable UUID id) {
        if (lifecycle.markPaid(id)) return ResponseEntity.ok(Map.of("status", "CONFIRMED"));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Reservation not found, already paid, or expired"));
    }
}
