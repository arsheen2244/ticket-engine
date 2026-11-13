import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, delay } from "msw";
import { server, booking } from "@/test/server";
import { BookingPanel } from "./BookingPanel";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }) }));

const fast = { sleep: async () => undefined };

describe("BookingPanel", () => {
  beforeEach(() => push.mockReset());

  it("asks logged-out visitors to log in instead of showing the Book button", () => {
    render(<BookingPanel eventId={7} remaining={10} signedIn={false} />);
    expect(screen.queryByRole("button", { name: /book/i })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Log in" })).toHaveAttribute("href", "/login?next=/events/7");
  });

  it("books once on a double click, shows progress, then opens the confirmation", async () => {
    const keys: (string | null)[] = [];
    server.use(
      http.post("*/api/bookings", async ({ request }) => {
        keys.push(request.headers.get("Idempotency-Key"));
        await delay(50);
        return HttpResponse.json(booking("QUEUED", keys[0]!), { status: 202 });
      }),
      http.get("*/api/bookings/:key", ({ params }) => HttpResponse.json(booking("PENDING_PAYMENT", String(params.key)))),
    );
    render(<BookingPanel eventId={1} remaining={10} signedIn options={fast} />);
    const button = screen.getByRole("button", { name: "Book 1 ticket" });

    await userEvent.dblClick(button);

    expect(await screen.findByText("Booking in progress")).toBeInTheDocument();
    await waitFor(() => expect(push).toHaveBeenCalledTimes(1));
    expect(keys).toHaveLength(1);
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(push).toHaveBeenCalledWith(`/bookings/${keys[0]}?eventId=1`);
    expect(screen.getByText("Confirmed")).toBeInTheDocument();
  });

  it("shows sold out when the gate rejects the booking", async () => {
    server.use(http.post("*/api/bookings", () => HttpResponse.json({ error: "Sold out" }, { status: 409 })));
    render(<BookingPanel eventId={1} remaining={1} signedIn options={fast} />);
    await userEvent.click(screen.getByRole("button", { name: "Book 1 ticket" }));
    expect(await screen.findByText("Sold out", { selector: "p" })).toBeInTheDocument();
  });

  it("disables booking when the live count reaches zero", () => {
    render(<BookingPanel eventId={1} remaining={0} signedIn />);
    expect(screen.getByRole("button", { name: "Sold out" })).toBeDisabled();
  });

  it("shows failed and retries with the same key after a server error", async () => {
    const keys: (string | null)[] = [];
    let call = 0;
    server.use(
      http.post("*/api/bookings", ({ request }) => {
        keys.push(request.headers.get("Idempotency-Key"));
        return ++call === 1
          ? HttpResponse.json({ error: "Queue unavailable, please retry" }, { status: 503 })
          : HttpResponse.json(booking("PENDING_PAYMENT", keys[0]!), { status: 201 });
      }),
    );
    render(<BookingPanel eventId={1} remaining={5} signedIn options={fast} />);
    await userEvent.click(screen.getByRole("button", { name: "Book 1 ticket" }));
    expect(await screen.findByText("Booking failed")).toBeInTheDocument();
    expect(screen.getByText("Queue unavailable, please retry")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });
});
