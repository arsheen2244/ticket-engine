import { NextResponse, type NextRequest } from "next/server";
import { AUTH_COOKIE, decideRoute, readSession } from "@/lib/session";

/**
 * Keeps logged-out visitors away from /orders, /bookings and /admin, and non-admins away from /admin.
 * This is for a smooth UI only; the backend enforces the same rules on every API call.
 */
export function middleware(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const decision = decideRoute(pathname, readSession(request.cookies.get(AUTH_COOKIE)?.value));
  if (decision.action === "allow") return NextResponse.next();

  const url = request.nextUrl.clone();
  url.search = "";
  if (decision.action === "login") {
    url.pathname = "/login";
    url.searchParams.set("next", pathname + search);
  } else {
    url.pathname = "/";
    url.searchParams.set("denied", "1");
  }
  return NextResponse.redirect(url);
}

export const config = {
  matcher: ["/orders/:path*", "/bookings/:path*", "/admin/:path*"],
};
