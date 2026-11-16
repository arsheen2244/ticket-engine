import { api, ApiError } from "./api";
import type { BookingResponse, Order } from "./types";

/**
 * What the Book button shows. Matches the backend pipeline:
 *  in_progress → request sent, or accepted by the Redis gate and waiting in Kafka (queued)
 *  confirmed   → stored in Postgres (PENDING_PAYMENT, or CONFIRMED once paid)
 *  sold_out    → the Redis gate had no stock left
 *  failed      → anything else (limit reached, rejected by the pipeline, server busy, timeout)
 */
export type BookingView =
  | { phase: "idle" }
  | { phase: "in_progress"; key: string; queued: boolean }
  | { phase: "confirmed"; key: string; order: Order }
  | { phase: "sold_out" }
  | { phase: "failed"; message: string; retrySameKey: boolean };

export interface BookingOptions {
  /** Wait before each status check, in ms. The last value repeats until the timeout. */
  pollDelays?: number[];
  timeoutMs?: number;
  sleep?: (ms: number) => Promise<void>;
  now?: () => number;
}

const DEFAULTS = {
  pollDelays: [300, 500, 800, 1000, 1500],
  timeoutMs: 30_000,
  sleep: (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)),
  now: () => Date.now(),
};

/** A new idempotency key for one booking attempt. Retries of the same attempt reuse it. */
export function newBookingKey(): string {
  return crypto.randomUUID();
}

export function statusUrl(key: string, eventId: number): string {
  return `/api/bookings/${encodeURIComponent(key)}?eventId=${eventId}`;
}

/** Turns one booking/status response into the view, or null while still queued. */
export function viewOf(res: BookingResponse): BookingView | null {
  switch (res.status) {
    case "PENDING_PAYMENT":
    case "CONFIRMED":
      return res.order ? { phase: "confirmed", key: res.bookingKey, order: res.order } : null;
    case "EXPIRED":
      return { phase: "failed", message: "This reservation expired before payment.", retrySameKey: false };
    case "FAILED":
      return { phase: "failed", message: "The booking could not be completed and no tickets were taken.", retrySameKey: false };
    case "QUEUED":
      return null;
  }
}

export function viewOfError(e: unknown): BookingView {
  if (e instanceof ApiError) {
    if (e.status === 409 && e.message === "Sold out") return { phase: "sold_out" };
    if (e.status === 429) return { phase: "failed", message: "You have reached the limit of 4 tickets for this sale.", retrySameKey: false };
    if (e.status === 401) return { phase: "failed", message: "Your session ended. Please log in again.", retrySameKey: true };
    // 0 (network), 5xx: the request may or may not have arrived, so retry with the SAME key.
    const transient = e.status === 0 || e.status >= 500;
    return { phase: "failed", message: e.message, retrySameKey: transient };
  }
  return { phase: "failed", message: "Something went wrong. Please try again.", retrySameKey: true };
}

/**
 * Books tickets and follows the request until it has an outcome, reporting each state via onUpdate.
 * Sends exactly one POST; afterwards only reads the status, so it never books twice.
 */
export async function runBooking(
  eventId: number,
  qty: number,
  key: string,
  onUpdate: (view: BookingView) => void,
  options: BookingOptions = {},
): Promise<BookingView> {
  const { pollDelays, timeoutMs, sleep, now } = { ...DEFAULTS, ...options };
  const finish = (view: BookingView) => {
    onUpdate(view);
    return view;
  };

  onUpdate({ phase: "in_progress", key, queued: false });
  let res: BookingResponse;
  try {
    res = await api<BookingResponse>("/api/bookings", {
      method: "POST",
      headers: { "Idempotency-Key": key },
      json: { eventId, qty },
    });
  } catch (e) {
    return finish(viewOfError(e));
  }

  const started = now();
  for (let attempt = 0; ; attempt++) {
    const view = viewOf(res);
    if (view) return finish(view);
    onUpdate({ phase: "in_progress", key, queued: true });
    if (now() - started >= timeoutMs) {
      return finish({
        phase: "failed",
        message: "This is taking longer than usual. Check My orders in a minute.",
        retrySameKey: true,
      });
    }
    await sleep(pollDelays[Math.min(attempt, pollDelays.length - 1)]);
    try {
      res = await api<BookingResponse>(statusUrl(key, eventId));
    } catch (e) {
      if (e instanceof ApiError && e.status === 0) continue; // brief network blip: keep checking
      return finish(viewOfError(e));
    }
  }
}
