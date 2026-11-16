"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useBooking } from "@/lib/useBooking";
import type { BookingOptions } from "@/lib/booking";
import { Spinner } from "./ui";

const MAX_PER_USER = 4;

/** Quantity picker, Book button and the four booking states. */
export function BookingPanel({
  eventId,
  remaining,
  signedIn,
  options,
}: {
  eventId: number;
  remaining: number | null;
  signedIn: boolean;
  options?: BookingOptions;
}) {
  const router = useRouter();
  const { view, book, reset, busy } = useBooking(eventId, options);
  const [qty, setQty] = useState(1);
  const soldOut = remaining !== null && remaining <= 0;
  const maxQty = Math.max(1, Math.min(MAX_PER_USER, remaining ?? MAX_PER_USER));

  useEffect(() => {
    if (qty > maxQty) setQty(maxQty);
  }, [qty, maxQty]);

  useEffect(() => {
    if (view.phase === "confirmed") {
      router.push(`/bookings/${encodeURIComponent(view.key)}?eventId=${eventId}`);
    }
  }, [view, eventId, router]);

  if (!signedIn) {
    return (
      <div className="rounded-lg bg-slate-50 p-4 text-sm text-slate-700">
        <Link href={`/login?next=/events/${eventId}`} className="font-semibold text-indigo-700 hover:underline">
          Log in
        </Link>{" "}
        or{" "}
        <Link href={`/signup?next=/events/${eventId}`} className="font-semibold text-indigo-700 hover:underline">
          create an account
        </Link>{" "}
        to book tickets.
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <label className="block text-sm font-medium text-slate-700">
          Tickets
          <select
            className="input mt-1 sm:w-28"
            value={qty}
            onChange={(e) => setQty(Number(e.target.value))}
            disabled={busy || soldOut}
          >
            {Array.from({ length: maxQty }, (_, i) => i + 1).map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="btn-primary w-full py-3 text-base sm:w-auto sm:px-8"
          onClick={() => void book(qty)}
          disabled={busy || soldOut || view.phase === "confirmed"}
          aria-describedby="booking-status"
        >
          {busy ? (
            <>
              <Spinner /> Booking…
            </>
          ) : soldOut ? (
            "Sold out"
          ) : view.phase === "failed" && view.retrySameKey ? (
            "Try again"
          ) : (
            `Book ${qty} ${qty === 1 ? "ticket" : "tickets"}`
          )}
        </button>
      </div>
      <p className="text-xs text-slate-500">Up to {MAX_PER_USER} tickets per person. Unpaid reservations are released after 5 minutes.</p>

      <div id="booking-status" aria-live="polite">
        {view.phase === "in_progress" && (
          <div className="flex items-start gap-3 rounded-lg border border-indigo-200 bg-indigo-50 p-4 text-indigo-900">
            <Spinner className="mt-0.5 h-5 w-5" />
            <div>
              <p className="font-semibold">Booking in progress</p>
              <p className="text-sm">
                {view.queued
                  ? "Your tickets are held. We're confirming the reservation, this usually takes a second."
                  : "Sending your request…"}
              </p>
            </div>
          </div>
        )}
        {view.phase === "confirmed" && (
          <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-emerald-900">
            <p className="font-semibold">Confirmed</p>
            <p className="text-sm">Your tickets are reserved. Opening your confirmation…</p>
          </div>
        )}
        {(view.phase === "sold_out" || (soldOut && view.phase === "idle")) && (
          <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-red-900">
            <p className="font-semibold">Sold out</p>
            <p className="text-sm">
              {view.phase === "sold_out"
                ? `There weren't enough tickets left for ${qty}. ${remaining ? "Try fewer tickets." : "Watch this page: unpaid tickets come back after 5 minutes."}`
                : "All tickets are taken. Unpaid reservations are released after 5 minutes, so check back."}
            </p>
            {view.phase === "sold_out" && remaining !== null && remaining > 0 && (
              <button type="button" className="btn-secondary mt-3" onClick={reset}>
                Choose again
              </button>
            )}
          </div>
        )}
        {view.phase === "failed" && (
          <div role="alert" className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-amber-900">
            <p className="font-semibold">Booking failed</p>
            <p className="text-sm">{view.message}</p>
            {!view.retrySameKey && (
              <button type="button" className="btn-secondary mt-3" onClick={reset}>
                Start over
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
