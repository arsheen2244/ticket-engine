package com.example.ticketengine.api;

import com.example.ticketengine.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** The signed-in user's own reservations. Other users' orders are invisible (404). */
@RestController
@RequestMapping("/api/me/orders")
public class MyOrdersController {

    static final int LIMIT = 100;

    private final WebAppRepository repo;

    public MyOrdersController(WebAppRepository repo) { this.repo = repo; }

    @GetMapping
    public List<OrderView> list(@AuthenticationPrincipal Jwt token) {
        return repo.ordersOf(CurrentUser.reservationUserId(token), BookingController.keyPrefix(token), LIMIT);
    }

    @GetMapping("/{id}")
    public OrderView get(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return find(token, id);
    }

    /** Mock payment: confirms a pending reservation that has not expired yet. */
    @PostMapping("/{id}/pay")
    public OrderView pay(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        OrderView order = find(token, id);
        if (!repo.payOwn(id, CurrentUser.reservationUserId(token))) {
            String why = switch (order.status()) {
                case "CONFIRMED" -> "This order is already paid";
                case "EXPIRED" -> "This reservation expired before payment";
                default -> "The payment window has closed";
            };
            throw new ResponseStatusException(HttpStatus.CONFLICT, why);
        }
        return find(token, id);
    }

    private OrderView find(Jwt token, UUID id) {
        return repo.orderById(CurrentUser.reservationUserId(token), BookingController.keyPrefix(token), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }
}
