"use client";

import { useEffect, useState } from "react";
import { api } from "./api";
import type { EventItem, StockUpdate } from "./types";

export type LiveStatus = "connecting" | "live" | "polling";

const POLL_MS = 3000;
const ERRORS_BEFORE_POLLING = 3;

/**
 * Live remaining-ticket count for one event. Uses Server-Sent Events (EventSource reconnects by itself);
 * if the stream keeps failing (for example behind a proxy that blocks it), falls back to polling.
 */
export function useLiveStock(eventId: number, initial: number | null) {
  const [remaining, setRemaining] = useState<number | null>(initial);
  const [status, setStatus] = useState<LiveStatus>("connecting");

  useEffect(() => {
    setRemaining(initial);
  }, [initial]);

  useEffect(() => {
    let source: EventSource | null = null;
    let timer: ReturnType<typeof setInterval> | null = null;
    let errors = 0;

    const startPolling = () => {
      source?.close();
      setStatus("polling");
      timer = setInterval(() => {
        api<EventItem>(`/api/events/${eventId}`)
          .then((e) => setRemaining(e.remaining))
          .catch(() => undefined); // keep the last known count
      }, POLL_MS);
    };

    if (typeof EventSource === "undefined") {
      startPolling();
    } else {
      source = new EventSource(`/api/events/${eventId}/stream`);
      source.addEventListener("stock", (msg) => {
        errors = 0;
        setStatus("live");
        setRemaining((JSON.parse((msg as MessageEvent<string>).data) as StockUpdate).remaining);
      });
      source.onerror = () => {
        errors += 1;
        if (errors >= ERRORS_BEFORE_POLLING) startPolling();
        else setStatus("connecting");
      };
    }
    return () => {
      source?.close();
      if (timer) clearInterval(timer);
    };
  }, [eventId]);

  return { remaining, status };
}
