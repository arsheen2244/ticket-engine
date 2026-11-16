import { describe, expect, it } from "vitest";
import { decideRoute, readSession, safeNext } from "./session";

function token(payload: object): string {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "HS256" })}.${b64(payload)}.signature`;
}

const NOW = 1_800_000_000;

describe("readSession", () => {
  it("reads roles and expiry from a token that has not expired", () => {
    expect(readSession(token({ exp: NOW + 60, roles: ["ADMIN"] }), NOW)).toEqual({ roles: ["ADMIN"], expiresAt: NOW + 60 });
  });

  it("treats missing, malformed or expired tokens as logged out", () => {
    expect(readSession(undefined, NOW)).toBeNull();
    expect(readSession("not-a-jwt", NOW)).toBeNull();
    expect(readSession("a.%%%.c", NOW)).toBeNull();
    expect(readSession(token({ exp: NOW - 1, roles: ["USER"] }), NOW)).toBeNull();
  });
});

describe("decideRoute (middleware)", () => {
  const user = { roles: ["USER" as const], expiresAt: NOW + 60 };
  const admin = { roles: ["ADMIN" as const], expiresAt: NOW + 60 };

  it("lets anyone see public pages", () => {
    expect(decideRoute("/", null)).toEqual({ action: "allow" });
    expect(decideRoute("/events/3", null)).toEqual({ action: "allow" });
  });

  it("sends logged-out visitors of protected pages to login", () => {
    expect(decideRoute("/orders", null)).toEqual({ action: "login" });
    expect(decideRoute("/bookings/abc", null)).toEqual({ action: "login" });
    expect(decideRoute("/admin", null)).toEqual({ action: "login" });
  });

  it("keeps plain users out of the admin page only", () => {
    expect(decideRoute("/orders", user)).toEqual({ action: "allow" });
    expect(decideRoute("/admin", user)).toEqual({ action: "forbidden" });
    expect(decideRoute("/admin", admin)).toEqual({ action: "allow" });
    expect(decideRoute("/administrator", null)).toEqual({ action: "allow" }); // prefix match is per path segment
  });
});

describe("safeNext", () => {
  it("only redirects to our own pages", () => {
    expect(safeNext("/events/1")).toBe("/events/1");
    expect(safeNext("https://evil.example")).toBe("/");
    expect(safeNext("//evil.example")).toBe("/");
    expect(safeNext(null)).toBe("/");
  });
});
