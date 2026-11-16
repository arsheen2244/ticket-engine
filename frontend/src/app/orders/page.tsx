"use client";

import Link from "next/link";
import { useState } from "react";
import { api, errorMessage } from "@/lib/api";
import { useApiData } from "@/lib/useApiData";
import { formatDateTime } from "@/lib/format";
import type { Order } from "@/lib/types";
import { OrderStatusBadge } from "@/components/OrderStatusBadge";
import { EmptyState, ErrorState, LoadingBlock, PageHeader, Spinner } from "@/components/ui";

function OrderRow({ order, onChange }: { order: Order; onChange: (o: Order) => void }) {
  const [paying, setPaying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const payable = order.status === "PENDING_PAYMENT" && new Date(order.expiresAt).getTime() > Date.now();

  const pay = async () => {
    setPaying(true);
    setError(null);
    try {
      onChange(await api<Order>(`/api/me/orders/${order.id}/pay`, { method: "POST" }));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPaying(false);
    }
  };

  return (
    <li className="card flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0">
        <Link
          href={`/bookings/${encodeURIComponent(order.bookingKey)}?eventId=${order.eventId}`}
          className="font-medium text-slate-900 hover:text-indigo-700"
        >
          {order.eventName}
        </Link>
        <p className="text-sm text-slate-600">
          {order.qty} {order.qty === 1 ? "ticket" : "tickets"} · {formatDateTime(order.createdAt)}
        </p>
        {error && <p role="alert" className="mt-1 text-sm text-red-700">{error}</p>}
      </div>
      <div className="flex items-center gap-3">
        <OrderStatusBadge status={order.status} />
        {payable && (
          <button type="button" className="btn-primary" onClick={pay} disabled={paying}>
            {paying ? <Spinner /> : "Pay"}
          </button>
        )}
      </div>
    </li>
  );
}

export default function MyOrdersPage() {
  const { data, error, loading, reload, setData } = useApiData<Order[]>("/api/me/orders", 15_000);

  return (
    <>
      <PageHeader title="My orders" />
      {loading && !data ? (
        <LoadingBlock label="Loading your orders…" />
      ) : error && !data ? (
        <ErrorState error={error} onRetry={reload} />
      ) : !data || data.length === 0 ? (
        <EmptyState title="No orders yet">
          <Link href="/" className="font-medium text-indigo-700 hover:underline">Browse the sales</Link> and grab a ticket.
        </EmptyState>
      ) : (
        <ul className="space-y-3">
          {data.map((o) => (
            <OrderRow key={o.id} order={o} onChange={(next) => setData(data.map((x) => (x.id === next.id ? next : x)))} />
          ))}
        </ul>
      )}
    </>
  );
}
