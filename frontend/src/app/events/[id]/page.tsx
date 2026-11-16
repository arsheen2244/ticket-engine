"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useApiData } from "@/lib/useApiData";
import { useLiveStock } from "@/lib/useLiveStock";
import type { EventItem } from "@/lib/types";
import { BookingPanel } from "@/components/BookingPanel";
import { ErrorState, LoadingBlock } from "@/components/ui";
import { ApiError } from "@/lib/api";

function LiveDot({ status }: { status: "connecting" | "live" | "polling" }) {
  const label = status === "live" ? "Live" : status === "polling" ? "Updating every few seconds" : "Connecting…";
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-600">
      <span className={`h-2 w-2 rounded-full ${status === "live" ? "animate-pulse bg-emerald-500" : "bg-amber-400"}`} aria-hidden />
      {label}
    </span>
  );
}

export default function EventDetailPage() {
  const id = Number(useParams<{ id: string }>().id);
  const { state } = useAuth();
  const { data: event, error, loading, reload } = useApiData<EventItem>(Number.isFinite(id) ? `/api/events/${id}` : null);
  const live = useLiveStock(id, event?.remaining ?? null);

  if (!Number.isFinite(id) || (error instanceof ApiError && error.status === 404)) {
    return (
      <div className="card p-8 text-center">
        <p className="font-medium">This sale does not exist.</p>
        <Link href="/" className="btn-secondary mt-4">Back to all sales</Link>
      </div>
    );
  }
  if (loading && !event) return <LoadingBlock label="Loading sale…" />;
  if (error || !event) return <ErrorState error={error} onRetry={reload} />;

  const remaining = live.remaining ?? event.remaining;
  const pct = event.totalTickets > 0 ? (remaining / event.totalTickets) * 100 : 0;

  return (
    <div className="mx-auto max-w-2xl">
      <Link href="/" className="text-sm text-indigo-700 hover:underline">← All sales</Link>
      <div className="card mt-3 overflow-hidden">
        <div className="border-b border-slate-200 p-6">
          <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">{event.name}</h1>
          <p className="mt-1 text-sm text-slate-600">{event.totalTickets.toLocaleString()} tickets in this sale</p>
        </div>
        <div className="space-y-6 p-6">
          <div>
            <div className="flex items-end justify-between gap-3">
              <div>
                <p className="text-sm text-slate-600">Tickets remaining</p>
                <p className="text-4xl font-bold tabular-nums text-slate-900 sm:text-5xl" data-testid="remaining" aria-live="polite">
                  {remaining.toLocaleString()}
                </p>
              </div>
              <LiveDot status={live.status} />
            </div>
            <div className="mt-3 h-3 overflow-hidden rounded-full bg-slate-100" aria-hidden>
              <div className="h-full rounded-full bg-indigo-500 transition-all duration-500" style={{ width: `${pct}%` }} />
            </div>
          </div>
          {state.status === "loading" ? (
            <LoadingBlock label="Checking your session…" />
          ) : (
            <BookingPanel eventId={event.id} remaining={remaining} signedIn={state.status === "authenticated"} />
          )}
        </div>
      </div>
    </div>
  );
}
