package com.example.ticketengine;

import java.util.UUID;

public record Reservation(UUID id, long eventId, String userId, int qty, String status) {}
