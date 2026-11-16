import type { NextConfig } from "next";

// Where the Spring Boot backend runs. The browser only ever talks to this Next.js server, which
// forwards /api/* to the backend. That keeps the login cookie and the live SSE stream same-origin.
// Rewrites are fixed at build time, so set BACKEND_URL before `next build` (Docker passes it as a build arg).
const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  output: "standalone", // small self-contained server for the Docker image
  poweredByHeader: false,
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }];
  },
};

export default nextConfig;
