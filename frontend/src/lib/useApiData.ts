"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "./api";

/**
 * Loads JSON from the backend for a page: gives loading, error and data, plus reload().
 * With refreshMs it refetches quietly in the background while the tab is visible.
 */
export function useApiData<T>(path: string | null, refreshMs?: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(path !== null);
  const generation = useRef(0);

  const load = useCallback(
    async (quiet: boolean) => {
      if (path === null) return;
      const mine = ++generation.current; // ignore answers to older requests
      if (!quiet) setLoading(true);
      try {
        const result = await api<T>(path);
        if (mine === generation.current) {
          setData(result);
          setError(null);
        }
      } catch (e) {
        if (mine === generation.current && !quiet) setError(e);
      } finally {
        if (mine === generation.current && !quiet) setLoading(false);
      }
    },
    [path],
  );

  useEffect(() => {
    void load(false);
  }, [load]);

  useEffect(() => {
    if (!refreshMs) return;
    const id = setInterval(() => {
      if (document.visibilityState === "visible") void load(true);
    }, refreshMs);
    return () => clearInterval(id);
  }, [load, refreshMs]);

  return { data, error, loading, reload: () => load(false), setData };
}
