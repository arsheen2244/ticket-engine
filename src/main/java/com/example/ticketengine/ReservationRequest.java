package com.example.ticketengine;

/** Message sent to Kafka after the Redis gate accepted a request. */
public record ReservationRequest(long eventId, String userId, int qty, String idempotencyKey) {}
