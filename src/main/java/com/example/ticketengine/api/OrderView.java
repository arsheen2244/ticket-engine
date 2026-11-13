package com.example.ticketengine.api;

import java.time.Instant;
import java.util.UUID;

/** One of the signed-in user's reservations. {@code bookingKey} is the client's idempotency key. */
public record OrderView(UUID id, long eventId, String eventName, int qty, String status,
                        String bookingKey, Instant createdAt, Instant expiresAt) {}
