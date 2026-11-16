"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { api, errorMessage } from "@/lib/api";
import { statusUrl } from "@/lib/booking";
import { formatCountdown, formatDateTime } from "@/lib/format";
import type { BookingResponse, Order } from "@/lib/types";
import { OrderStatusBadge } from "@/components/OrderStatusBadge";
import { ErrorState, LoadingBlock, Spinner } from "@/components/ui";

const POLL_MS = 1000;

function useSecondsLeft(expiresAt: string | undefined) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!expiresAt) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [expiresAt]);
  return expiresAt ? (new Date(expiresAt).getTime() - now) / 1000 : 0;
}

function Confirmation() {
  const key = decodeURIComponent(useParams<{ key: string }>().key);
  const eventId = Number(useSearchParams().get("eventId"));
  const [booking, setBooking] = useState<BookingResponse | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [paying, setPaying] = useState(false);
  const [payError, setPayError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setBooking(await api<BookingResponse>(statusUrl(key, eventId)));
      setError(null);
    } catch (e) {
      setError(e);
    }
  }, [key, eventId]);

  useEffect(() => {
    void load();
  }, [load]);

  // While the Kafka consumer has not stored it yet, keep checking.
  useEffect(() => {
    if (booking?.status !== "QUEUED") return;
    const id = setInterval(() => void load(), POLL_MS);
    return () => clearInterval(id);
  }, [booking?.status, load]);

  const order: Order | null = booking?.order ?? null;
  const secondsLeft = useSecondsLeft(order?.status === "PENDING_PAYMENT" ? order.expiresAt : undefined);

  const pay = async () => {
    if (!order || paying) return;
    setPaying(true);
    setPayError(null);
    try {
      const paid = await api<Order>(`/api/me/orders/${order.id}/pay`, { method: "POST" });
      setBooking((b) => (b ? { ...b, status: paid.status, order: paid } : b));
    } catch (e) {
      setPayError(errorMessage(e));
      void load();
    } finally {
      setPaying(false);
    }
  };

  if (!Number.isFinite(eventId) || eventId <= 0) {
    return <ErrorState error={new Error("This confirmation link is incomplete.")} />;
  }
  if (error && !booking) return <ErrorState error={error} onRetry={() => void load()} />;
  if (!booking) return <LoadingBlock label="Loading your booking…" />;

  if (booking.status === "QUEUED") {
    return (
      <div className="card flex items-start gap-4 p-6" aria-live="polite">
        <Spinner className="mt-1 h-6 w-6 text-indigo-600" />
        <div>
          <h1 className="text-xl font-semibold">Booking in progress</h1>
          <p className="mt-1 text-slate-600">Your tickets are held while we store the reservation. This page updates by itself.</p>
        </div>
      </div>
    );
  }

  if (booking.status === "FAILED" || !order) {
    return (
      <div role="alert" className="card border-amber-200 p-6">
        <h1 className="text-xl font-semibold text-amber-900">Booking failed</h1>
        <p className="mt-1 text-slate-700">This booking could not be completed and no tickets were taken.</p>
        <Link href={`/events/${eventId}`} className="btn-primary mt-4">Back to the sale</Link>
      </div>
    );
  }

  const expired = order.status === "EXPIRED" || (order.status === "PENDING_PAYMENT" && secondsLeft <= 0);

  return (
    <div className="mx-auto max-w-xl">
      <div className="card overflow-hidden">
        <div className={`p-6 ${order.status === "CONFIRMED" ? "bg-emerald-50" : expired ? "bg-slate-50" : "bg-indigo-50"}`}>
          <p className="text-sm font-medium text-slate-600">Booking confirmation</p>
          <h1 className="mt-1 text-2xl font-semibold">
            {order.status === "CONFIRMED" ? "You're going! 🎉" : expired ? "Reservation expired" : "Tickets reserved"}
          </h1>
          <div className="mt-2"><OrderStatusBadge status={expired ? "EXPIRED" : order.status} /></div>
        </div>
        <dl className="grid grid-cols-1 gap-4 p-6 text-sm sm:grid-cols-2">
          <div><dt className="text-slate-500">Sale</dt><dd className="font-medium">{order.eventName}</dd></div>
          <div><dt className="text-slate-500">Tickets</dt><dd className="font-medium">{order.qty}</dd></div>
          <div><dt className="text-slate-500">Booked</dt><dd className="font-medium">{formatDateTime(order.createdAt)}</dd></div>
          <div><dt className="text-slate-500">Reference</dt><dd className="break-all font-mono text-xs">{order.id}</dd></div>
        </dl>
        <div className="border-t border-slate-200 p-6">
          {order.status === "PENDING_PAYMENT" && !expired && (
            <>
              <p className="text-sm text-slate-700">
                Pay within <span className="font-semibold tabular-nums">{formatCountdown(secondsLeft)}</span> or the tickets go back on sale.
              </p>
              <button type="button" className="btn-primary mt-3 w-full sm:w-auto" onClick={pay} disabled={paying}>
                {paying ? (<><Spinner /> Paying…</>) : "Pay now (demo, no card needed)"}
              </button>
            </>
          )}
          {expired && <p className="text-sm text-slate-700">The 5-minute payment window passed, so these tickets were released.</p>}
          {order.status === "CONFIRMED" && <p className="text-sm text-slate-700">Payment received. Your tickets are confirmed.</p>}
          {payError && <p role="alert" className="mt-3 text-sm text-red-700">{payError}</p>}
          <div className="mt-4 flex flex-wrap gap-3">
            <Link href="/orders" className="btn-secondary">My orders</Link>
            <Link href={`/events/${order.eventId}`} className="btn-secondary">Back to the sale</Link>
          </div>
        </div>
      </div>
    </div>
  );
}

export default function BookingConfirmationPage() {
  return (
    <Suspense fallback={<LoadingBlock label="Loading your booking…" />}>
      <Confirmation />
    </Suspense>
  );
}
