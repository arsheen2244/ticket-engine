import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server, booking } from "@/test/server";
import { runBooking, type BookingView } from "./booking";

const KEY = "key-12345678";
const instant = { sleep: async () => undefined };

/** Runs a booking and records every state the UI would have shown. */
async function run(options = {}) {
  const seen: BookingView[] = [];
  const result = await runBooking(1, 2, KEY, (v) => seen.push(v), { ...instant, ...options });
  return { result, phases: seen.map((v) => v.phase + ("queued" in v && v.queued ? ":queued" : "")) };
}

describe("runBooking", () => {
  it("sends one POST with the idempotency key and quantity, and confirms on 201", async () => {
    const posts: { key: string | null; body: unknown }[] = [];
    server.use(
      http.post("*/api/bookings", async ({ request }) => {
        posts.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
        return HttpResponse.json(booking("PENDING_PAYMENT", KEY), { status: 201 });
      }),
    );
    const { result, phases } = await run();
    expect(posts).toEqual([{ key: KEY, body: { eventId: 1, qty: 2 } }]);
    expect(phases).toEqual(["in_progress", "confirmed"]);
    expect(result).toMatchObject({ phase: "confirmed", key: KEY });
  });

  it("follows a queued (202) booking through the Kafka pipeline until it is stored", async () => {
    let polls = 0;
    let posts = 0;
    server.use(
      http.post("*/api/bookings", () => {
        posts++;
        return HttpResponse.json(booking("QUEUED", KEY), { status: 202 });
      }),
      http.get(`*/api/bookings/${KEY}`, ({ request }) => {
        expect(new URL(request.url).searchParams.get("eventId")).toBe("1");
        polls++;
        return HttpResponse.json(polls < 3 ? booking("QUEUED", KEY) : booking("PENDING_PAYMENT", KEY));
      }),
    );
    const { result, phases } = await run();
    expect(posts).toBe(1); // polling never re-books
    expect(polls).toBe(3);
    expect(phases[0]).toBe("in_progress");
    expect(phases).toContain("in_progress:queued");
    expect(result.phase).toBe("confirmed");
  });

  it("reports sold out on 409 Sold out", async () => {
    server.use(http.post("*/api/bookings", () => HttpResponse.json({ error: "Sold out" }, { status: 409 })));
    expect((await run()).result).toEqual({ phase: "sold_out" });
  });

  it("reports failed when the pipeline rejects a queued booking", async () => {
    server.use(
      http.post("*/api/bookings", () => HttpResponse.json(booking("QUEUED", KEY), { status: 202 })),
      http.get(`*/api/bookings/${KEY}`, () => HttpResponse.json(booking("FAILED", KEY))),
    );
    expect((await run()).result).toMatchObject({ phase: "failed", retrySameKey: false });
  });

  it("explains the per-user limit on 429", async () => {
    server.use(http.post("*/api/bookings", () => HttpResponse.json({ error: "limit" }, { status: 429 })));
    expect((await run()).result).toMatchObject({ phase: "failed", message: expect.stringContaining("limit of 4") });
  });

  it("keeps the same key for a retry after a network error", async () => {
    server.use(http.post("*/api/bookings", () => HttpResponse.error()));
    expect((await run()).result).toMatchObject({ phase: "failed", retrySameKey: true });
  });

  it("gives up politely if the booking stays queued too long", async () => {
    let t = 0;
    server.use(
      http.post("*/api/bookings", () => HttpResponse.json(booking("QUEUED", KEY), { status: 202 })),
      http.get(`*/api/bookings/${KEY}`, () => HttpResponse.json(booking("QUEUED", KEY))),
    );
    const { result } = await run({ timeoutMs: 1000, now: () => (t += 400) });
    expect(result).toMatchObject({ phase: "failed", message: expect.stringContaining("My orders"), retrySameKey: true });
  });
});
