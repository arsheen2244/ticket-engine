import type { Role } from "./types";

export const AUTH_COOKIE = "access_token";

/** Pages that need a login, and the ones that also need the ADMIN role. */
export const PROTECTED_PREFIXES = ["/orders", "/bookings", "/admin"];
export const ADMIN_PREFIXES = ["/admin"];

export interface Session {
  roles: Role[];
  expiresAt: number; // epoch seconds
}

/**
 * Reads the roles and expiry from the JWT's payload WITHOUT checking its signature.
 * Only used to decide which page to show; the backend verifies the token on every API call.
 */
export function readSession(token: string | undefined, nowSeconds = Date.now() / 1000): Session | null {
  if (!token) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const payload = JSON.parse(atob(padded)) as { exp?: number; roles?: Role[] };
    if (typeof payload.exp !== "number" || payload.exp <= nowSeconds) return null;
    return { roles: Array.isArray(payload.roles) ? payload.roles : [], expiresAt: payload.exp };
  } catch {
    return null;
  }
}

const matches = (path: string, prefixes: string[]) =>
  prefixes.some((p) => path === p || path.startsWith(p + "/"));

export type RouteDecision = { action: "allow" } | { action: "login" } | { action: "forbidden" };

/** What the middleware does for a page request. */
export function decideRoute(pathname: string, session: Session | null): RouteDecision {
  if (!matches(pathname, PROTECTED_PREFIXES)) return { action: "allow" };
  if (!session) return { action: "login" };
  if (matches(pathname, ADMIN_PREFIXES) && !session.roles.includes("ADMIN")) return { action: "forbidden" };
  return { action: "allow" };
}

/** Only allow redirects back to our own pages after login (no open redirects). */
export function safeNext(next: string | null | undefined): string {
  return next && next.startsWith("/") && !next.startsWith("//") ? next : "/";
}
