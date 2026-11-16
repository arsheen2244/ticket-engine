// Shapes of the backend's JSON responses.

export type Role = "USER" | "ADMIN";

export interface User {
  id: number;
  email: string;
  role: Role;
}

export interface EventItem {
  id: number;
  name: string;
  totalTickets: number;
  remaining: number;
  soldOut: boolean;
  createdAt: string;
}

/** Stored reservation states. */
export type OrderStatus = "PENDING_PAYMENT" | "CONFIRMED" | "EXPIRED";

export interface Order {
  id: string;
  eventId: number;
  eventName: string;
  qty: number;
  status: OrderStatus;
  bookingKey: string;
  createdAt: string;
  expiresAt: string;
}

/** QUEUED: accepted by the Redis gate, waiting for the Kafka consumer. FAILED: rejected and released. */
export type BookingStatus = "QUEUED" | "FAILED" | OrderStatus;

export interface BookingResponse {
  status: BookingStatus;
  bookingKey: string;
  eventId: number;
  order: Order | null;
}

export interface StockUpdate {
  eventId: number;
  remaining: number;
  soldOut: boolean;
}

export interface SaleStats {
  id: number;
  name: string;
  totalTickets: number;
  remaining: number;
  dbAvailable: number;
  reservations: number;
  ticketsPending: number;
  ticketsConfirmed: number;
  ticketsExpired: number;
  ticketsSold: number;
  oversold: boolean;
}

export interface Dashboard {
  totals: { events: number; users: number; tickets_confirmed: number };
  sales: SaleStats[];
}
