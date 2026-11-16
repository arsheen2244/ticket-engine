"use client";

import Link from "next/link";
import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { useApiData } from "@/lib/useApiData";
import type { EventItem } from "@/lib/types";
import { Badge, EmptyState, ErrorState, LoadingCards, PageHeader } from "@/components/ui";
import { plural } from "@/lib/format";

function EventCard({ event }: { event: EventItem }) {
  const pct = event.totalTickets > 0 ? Math.round((event.remaining / event.totalTickets) * 100) : 0;
  return (
    <Link
      href={`/events/${event.id}`}
      className="card group flex flex-col gap-4 p-5 transition hover:border-indigo-300 hover:shadow-md"
    >
      <div className="flex items-start justify-between gap-3">
        <h2 className="font-semibold text-slate-900 group-hover:text-indigo-700">{event.name}</h2>
        {event.soldOut ? <Badge tone="red">Sold out</Badge> : pct <= 10 ? <Badge tone="amber">Almost gone</Badge> : <Badge tone="green">On sale</Badge>}
      </div>
      <div>
        <p className="text-sm text-slate-600">
          {event.soldOut ? "No tickets left" : `${plural(event.remaining, "ticket")} left of ${event.totalTickets.toLocaleString()}`}
        </p>
        <div className="mt-2 h-2 overflow-hidden rounded-full bg-slate-100" aria-hidden>
          <div className={`h-full rounded-full ${event.soldOut ? "bg-slate-300" : "bg-indigo-500"}`} style={{ width: `${pct}%` }} />
        </div>
      </div>
    </Link>
  );
}

function DeniedNotice() {
  const denied = useSearchParams().get("denied");
  if (!denied) return null;
  return (
    <p role="alert" className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
      That page is for admins only.
    </p>
  );
}

export default function EventsPage() {
  const { data, error, loading, reload } = useApiData<EventItem[]>("/api/events", 10_000);

  return (
    <>
      <Suspense fallback={null}>
        <DeniedNotice />
      </Suspense>
      <PageHeader title="Flash sales">
        <p className="text-sm text-slate-600">Limited tickets. Counts update live on each sale page.</p>
      </PageHeader>
      {loading && !data ? (
        <LoadingCards />
      ) : error && !data ? (
        <ErrorState error={error} onRetry={reload} />
      ) : !data || data.length === 0 ? (
        <EmptyState title="No sales yet">An admin can create one from the Admin page.</EmptyState>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {data.map((e) => (
            <EventCard key={e.id} event={e} />
          ))}
        </div>
      )}
    </>
  );
}
