package com.example.ticketengine.api;

import com.example.ticketengine.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** ADMIN-only (enforced in SecurityConfig): create sales, reset them, and see how they are selling. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record CreateSale(@NotBlank @Size(max = 120) String name, @Min(1) @Max(1_000_000) int totalTickets) {}

    /** {@code remaining} is the live Redis count; {@code oversold} should always be false. */
    public record SaleStats(long id, String name, int totalTickets, long remaining, int dbAvailable,
                            long reservations, long ticketsPending, long ticketsConfirmed, long ticketsExpired,
                            long ticketsSold, boolean oversold) {}

    public record Dashboard(Map<String, Object> totals, List<SaleStats> sales) {}

    private final ReservationService reservations;
    private final EventCatalog catalog;
    private final WebAppRepository repo;

    public AdminController(ReservationService reservations, EventCatalog catalog, WebAppRepository repo) {
        this.reservations = reservations;
        this.catalog = catalog;
        this.repo = repo;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public EventView create(@Valid @RequestBody CreateSale req) {
        return catalog.get(reservations.createEvent(req.name().trim(), req.totalTickets()));
    }

    /** Deletes the event's reservations and restores full stock (for re-running a demo sale). */
    @PostMapping("/events/{id}/reset")
    public EventView reset(@PathVariable long id) {
        reservations.reset(id);
        return catalog.get(id);
    }

    @GetMapping("/stats")
    public Dashboard stats() {
        List<SaleStats> sales = repo.salesStats(EventCatalog.LIST_LIMIT).stream().map(r -> {
            // Pending and confirmed tickets are held; expired ones went back on sale.
            long sold = r.ticketsPending() + r.ticketsConfirmed();
            return new SaleStats(r.id(), r.name(), r.totalTickets(), catalog.remaining(r.id(), r.dbAvailable()),
                    r.dbAvailable(), r.reservations(), r.ticketsPending(), r.ticketsConfirmed(), r.ticketsExpired(),
                    sold, sold > r.totalTickets());
        }).toList();
        return new Dashboard(repo.totals(), sales);
    }
}
