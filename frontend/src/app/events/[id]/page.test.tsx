import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/server";
import { AuthProvider } from "@/lib/auth";
import EventDetailPage from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "1" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));

/** Minimal stand-in for the browser's EventSource, so the test can push SSE messages. */
class FakeEventSource {
  static last: FakeEventSource | null = null;
  listeners: Record<string, ((e: MessageEvent<string>) => void)[]> = {};
  onerror: (() => void) | null = null;
  closed = false;
  constructor(public url: string) {
    FakeEventSource.last = this;
  }
  addEventListener(type: string, fn: (e: MessageEvent<string>) => void) {
    (this.listeners[type] ??= []).push(fn);
  }
  close() {
    this.closed = true;
  }
  emit(type: string, data: object) {
    this.listeners[type]?.forEach((fn) => fn(new MessageEvent(type, { data: JSON.stringify(data) })));
  }
}

describe("Event detail page", () => {
  beforeEach(() => {
    vi.stubGlobal("EventSource", FakeEventSource);
    server.use(
      http.get("*/api/auth/me", () => HttpResponse.json({ id: 1, email: "a@b.co", role: "USER" })),
      http.get("*/api/events/1", () =>
        HttpResponse.json({ id: 1, name: "Concert", totalTickets: 100, remaining: 40, soldOut: false, createdAt: "2026-11-13T10:00:00Z" }),
      ),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  it("shows the count, then live updates from the stream until sold out", async () => {
    render(<AuthProvider><EventDetailPage /></AuthProvider>);
    expect(screen.getByText("Loading sale…")).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Concert" })).toBeInTheDocument();
    expect(screen.getByTestId("remaining")).toHaveTextContent("40");
    expect(FakeEventSource.last?.url).toBe("/api/events/1/stream");

    act(() => FakeEventSource.last!.emit("stock", { eventId: 1, remaining: 12, soldOut: false }));
    expect(screen.getByTestId("remaining")).toHaveTextContent("12");
    expect(screen.getByText("Live")).toBeInTheDocument();

    act(() => FakeEventSource.last!.emit("stock", { eventId: 1, remaining: 0, soldOut: true }));
    expect(screen.getByRole("button", { name: "Sold out" })).toBeDisabled();
  });

  it("shows a not-found message for an unknown sale", async () => {
    server.use(http.get("*/api/events/1", () => HttpResponse.json({ error: "Event not found" }, { status: 404 })));
    render(<AuthProvider><EventDetailPage /></AuthProvider>);
    expect(await screen.findByText("This sale does not exist.")).toBeInTheDocument();
  });

  it("shows an error with a retry button when the server is down", async () => {
    server.use(http.get("*/api/events/1", () => HttpResponse.error()));
    render(<AuthProvider><EventDetailPage /></AuthProvider>);
    expect(await screen.findByRole("alert")).toHaveTextContent("Can't reach the server");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
