"use client";

import { ErrorState } from "@/components/ui";

/** Shown if a page crashes while rendering. */
export default function GlobalError({ error, reset }: { error: Error; reset: () => void }) {
  return <ErrorState error={error} onRetry={reset} />;
}
