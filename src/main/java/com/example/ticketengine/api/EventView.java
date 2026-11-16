package com.example.ticketengine.api;

import java.time.Instant;

/** An event as the web app shows it. {@code remaining} is the live count from the Redis gate. */
public record EventView(long id, String name, int totalTickets, long remaining, boolean soldOut, Instant createdAt) {}
