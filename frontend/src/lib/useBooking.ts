"use client";

import { useCallback, useRef, useState } from "react";
import { newBookingKey, runBooking, type BookingOptions, type BookingView } from "./booking";

/**
 * Book-button state for one event. Double-click safe in two ways:
 *  1. while a booking runs, further clicks are ignored (the ref updates synchronously, before React re-renders);
 *  2. every POST carries an Idempotency-Key, and a retry after a network error reuses it,
 *     so the backend returns the original booking instead of creating a second one.
 */
export function useBooking(eventId: number, options?: BookingOptions) {
  const [view, setView] = useState<BookingView>({ phase: "idle" });
  const inFlight = useRef(false);
  const retryKey = useRef<string | null>(null);

  const book = useCallback(
    async (qty: number) => {
      if (inFlight.current) return;
      inFlight.current = true;
      const key = retryKey.current ?? newBookingKey();
      try {
        const result = await runBooking(eventId, qty, key, setView, options);
        retryKey.current = result.phase === "failed" && result.retrySameKey ? key : null;
      } finally {
        inFlight.current = false;
      }
    },
    [eventId, options],
  );

  const reset = useCallback(() => {
    retryKey.current = null;
    setView({ phase: "idle" });
  }, []);

  return { view, book, reset, busy: view.phase === "in_progress" };
}
