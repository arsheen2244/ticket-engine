package com.example.ticketengine.api;

import com.example.ticketengine.ReservationService;
import com.example.ticketengine.Strategy;
import com.example.ticketengine.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/**
 * Booking for signed-in users. The user id comes from the login token, never from the request body.
 * The client sends an Idempotency-Key it generated once per booking attempt, so a double click or a
 * retry returns the same booking instead of buying twice. Runs the existing Redis gate: the async
 * Kafka pipeline when TICKET_ASYNC=true, otherwise the synchronous redis strategy.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    /** Booking states the UI shows. PENDING_PAYMENT, CONFIRMED and EXPIRED are stored reservation states. */
    public static final String QUEUED = "QUEUED";
    public static final String FAILED = "FAILED";

    static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    static final String DUPLICATE_IN_FLIGHT = "Duplicate request still in progress";

    public record BookingRequest(@Min(1) long eventId, @Min(1) @Max(4) int qty) {}

    /** {@code order} is present once Postgres has the reservation. */
    public record BookingResponse(String status, String bookingKey, long eventId, OrderView order) {}

    private final ReservationService reservations;
    private final EventCatalog catalog;
    private final WebAppRepository repo;

    public BookingController(ReservationService reservations, EventCatalog catalog, WebAppRepository repo) {
        this.reservations = reservations;
        this.catalog = catalog;
        this.repo = repo;
    }

    static String keyPrefix(Jwt token) { return CurrentUser.reservationUserId(token) + ":"; }

    @PostMapping
    public ResponseEntity<BookingResponse> book(@AuthenticationPrincipal Jwt token,
                                                @Valid @RequestBody BookingRequest req,
                                                @RequestHeader("Idempotency-Key") String clientKey) {
        requireValidKey(clientKey);
        catalog.get(req.eventId()); // 404 for an unknown event instead of "sold out"
        String userId = CurrentUser.reservationUserId(token);
        String storedKey = keyPrefix(token) + clientKey;
        try {
            if (reservations.asyncEnabled()) {
                var result = reservations.reserveAsync(req.eventId(), userId, req.qty(), storedKey);
                if (result.reservation() == null) return queued(req.eventId(), clientKey);
            } else {
                reservations.reserve(req.eventId(), userId, req.qty(), storedKey, Strategy.REDIS);
            }
        } catch (ResponseStatusException e) {
            // The same key is already being processed (a double click that slipped through): report it as queued.
            if (DUPLICATE_IN_FLIGHT.equals(e.getReason())) return queued(req.eventId(), clientKey);
            throw e;
        }
        OrderView order = repo.orderByKey(userId, keyPrefix(token), clientKey).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new BookingResponse(order.status(), clientKey, req.eventId(), order));
    }

    /**
     * Outcome of a booking. Stored reservation → its status. No row yet but the Redis gate still holds the
     * key → QUEUED. Neither → FAILED (the pipeline rejected it and gave the tickets back).
     */
    @GetMapping("/{clientKey}")
    public BookingResponse status(@AuthenticationPrincipal Jwt token, @PathVariable String clientKey,
                                  @RequestParam long eventId) {
        requireValidKey(clientKey);
        String userId = CurrentUser.reservationUserId(token);
        var order = repo.orderByKey(userId, keyPrefix(token), clientKey);
        if (order.isPresent()) return new BookingResponse(order.get().status(), clientKey, eventId, order.get());
        boolean queued = reservations.isAcceptedInRedis(eventId, keyPrefix(token) + clientKey);
        return new BookingResponse(queued ? QUEUED : FAILED, clientKey, eventId, null);
    }

    private static ResponseEntity<BookingResponse> queued(long eventId, String clientKey) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new BookingResponse(QUEUED, clientKey, eventId, null));
    }

    private static void requireValidKey(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 8 to 64 letters, digits, '-' or '_'");
        }
    }
}
