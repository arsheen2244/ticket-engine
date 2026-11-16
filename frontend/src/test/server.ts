import { setupServer } from "msw/node";
import type { BookingResponse, Order } from "@/lib/types";

/** Fake backend for tests. Each test adds the handlers it needs with server.use(...). */
export const server = setupServer();

export function order(overrides: Partial<Order> = {}): Order {
  return {
    id: "11111111-2222-3333-4444-555555555555",
    eventId: 1,
    eventName: "Concert",
    qty: 1,
    status: "PENDING_PAYMENT",
    bookingKey: "key-12345678",
    createdAt: "2026-11-13T10:00:00Z",
    expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    ...overrides,
  };
}

export function booking(status: BookingResponse["status"], key = "key-12345678", withOrder = true): BookingResponse {
  const stored = status === "PENDING_PAYMENT" || status === "CONFIRMED" || status === "EXPIRED";
  return { status, bookingKey: key, eventId: 1, order: stored && withOrder ? order({ status, bookingKey: key }) : null };
}
