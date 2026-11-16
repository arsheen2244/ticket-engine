"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api, errorMessage } from "@/lib/api";
import { useApiData } from "@/lib/useApiData";
import type { Dashboard, EventItem, SaleStats } from "@/lib/types";
import { Badge, EmptyState, ErrorState, LoadingBlock, PageHeader, Spinner } from "@/components/ui";

function CreateSaleForm({ onCreated }: { onCreated: (e: EventItem) => void }) {
  const [name, setName] = useState("");
  const [tickets, setTickets] = useState("1000");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<EventItem | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const totalTickets = Number(tickets);
    if (!name.trim()) return setError("Give the sale a name.");
    if (!Number.isInteger(totalTickets) || totalTickets < 1 || totalTickets > 1_000_000) {
      return setError("Tickets must be a whole number from 1 to 1,000,000.");
    }
    setSaving(true);
    setError(null);
    try {
      const event = await api<EventItem>("/api/admin/events", { method: "POST", json: { name: name.trim(), totalTickets } });
      setCreated(event);
      setName("");
      onCreated(event);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="card space-y-4 p-5" noValidate>
      <h2 className="font-semibold">Create a sale</h2>
      <label className="block text-sm font-medium text-slate-700">
        Name
        <input className="input mt-1" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} placeholder="Summer Festival 2026" />
      </label>
      <label className="block text-sm font-medium text-slate-700">
        Tickets
        <input className="input mt-1" type="number" min={1} max={1_000_000} value={tickets} onChange={(e) => setTickets(e.target.value)} />
      </label>
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
      {created && (
        <p className="text-sm text-emerald-800" role="status">
          Created <Link className="font-medium underline" href={`/events/${created.id}`}>{created.name}</Link>.
        </p>
      )}
      <button type="submit" className="btn-primary w-full" disabled={saving}>
        {saving ? (<><Spinner /> Creating…</>) : "Create sale"}
      </button>
    </form>
  );
}

function SaleRow({ sale, onReset }: { sale: SaleStats; onReset: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reset = async () => {
    if (!window.confirm(`Reset "${sale.name}"? This deletes its reservations and restores all ${sale.totalTickets} tickets.`)) return;
    setBusy(true);
    setError(null);
    try {
      await api(`/api/admin/events/${sale.id}/reset`, { method: "POST" });
      onReset();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  const pct = sale.totalTickets ? Math.round((sale.ticketsSold / sale.totalTickets) * 100) : 0;
  return (
    <tr className="align-top">
      <td className="py-3 pr-4">
        <Link href={`/events/${sale.id}`} className="font-medium hover:text-indigo-700">{sale.name}</Link>
        <div className="text-xs text-slate-500">#{sale.id}</div>
        {error && <div role="alert" className="text-xs text-red-700">{error}</div>}
      </td>
      <td className="py-3 pr-4 tabular-nums">{sale.ticketsSold.toLocaleString()} / {sale.totalTickets.toLocaleString()} <span className="text-slate-500">({pct}%)</span></td>
      <td className="py-3 pr-4 tabular-nums">{sale.remaining.toLocaleString()}</td>
      <td className="py-3 pr-4 tabular-nums">{sale.ticketsPending.toLocaleString()}</td>
      <td className="py-3 pr-4 tabular-nums">{sale.ticketsConfirmed.toLocaleString()}</td>
      <td className="py-3 pr-4 tabular-nums">{sale.ticketsExpired.toLocaleString()}</td>
      <td className="py-3 pr-4">{sale.oversold ? <Badge tone="red">Oversold</Badge> : <Badge tone="green">OK</Badge>}</td>
      <td className="py-3">
        <button type="button" className="btn-secondary px-3 py-1 text-xs" onClick={reset} disabled={busy}>
          {busy ? <Spinner className="h-3 w-3" /> : "Reset"}
        </button>
      </td>
    </tr>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="card p-4">
      <p className="text-sm text-slate-600">{label}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums">{value.toLocaleString()}</p>
    </div>
  );
}

export default function AdminPage() {
  const { data, error, loading, reload } = useApiData<Dashboard>("/api/admin/stats", 5_000);

  return (
    <>
      <PageHeader title="Admin">
        <p className="text-sm text-slate-600">Stats refresh every 5 seconds.</p>
      </PageHeader>
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[20rem_minmax(0,1fr)]">
        <div><CreateSaleForm onCreated={() => void reload()} /></div>
        <section className="min-w-0 space-y-4" aria-label="Sales stats">
          {loading && !data ? (
            <LoadingBlock label="Loading stats…" />
          ) : error && !data ? (
            <ErrorState error={error} onRetry={reload} />
          ) : data ? (
            <>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                <Stat label="Sales" value={data.totals.events} />
                <Stat label="Users" value={data.totals.users} />
                <Stat label="Tickets paid" value={data.totals.tickets_confirmed} />
              </div>
              {data.sales.length === 0 ? (
                <EmptyState title="No sales yet">Create one with the form.</EmptyState>
              ) : (
                <div className="card relative overflow-x-auto p-4">
                  <table className="w-full min-w-[44rem] text-left text-sm">
                    <thead className="text-xs uppercase text-slate-500">
                      <tr>
                        <th className="pb-2 pr-4 font-medium">Sale</th>
                        <th className="pb-2 pr-4 font-medium">Sold</th>
                        <th className="pb-2 pr-4 font-medium">Live left</th>
                        <th className="pb-2 pr-4 font-medium">Awaiting pay</th>
                        <th className="pb-2 pr-4 font-medium">Paid</th>
                        <th className="pb-2 pr-4 font-medium">Expired</th>
                        <th className="pb-2 pr-4 font-medium">Check</th>
                        <th className="pb-2 font-medium"><span className="sr-only">Actions</span></th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {data.sales.map((s) => <SaleRow key={s.id} sale={s} onReset={() => void reload()} />)}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          ) : null}
        </section>
      </div>
    </>
  );
}
