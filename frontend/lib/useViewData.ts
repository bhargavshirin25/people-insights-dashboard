"use client";

import { ApiError, api } from "@/lib/api";
import { useFilters } from "@/lib/filters";
import { useEffect, useState } from "react";

interface ViewData<T> {
  data: T | null;
  loading: boolean;
  error: ApiError | Error | null;
  reload: () => void;
  setData: (next: T) => void;
}

/**
 * Loads one view's data for the current filter state and reloads whenever any filter changes.
 *
 * Because every view uses this, a filter change updates all of them together, which is the
 * requirement. Responses are discarded if the filters moved on while a request was in flight, so a
 * slow reply can never repaint a view with figures from a stale selection.
 */
export function useViewData<T>(
  path: string,
  extra?: Record<string, string | undefined>,
  /**
   * Set false to hold the request back. Used to stop a second heavy view loading at the same time as
   * the first — an org-wide selection joins seven collections, and two of those in parallel is enough
   * to push the backend into a socket timeout.
   */
  enabled = true,
): ViewData<T> {
  const { query, filters } = useFilters();
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | Error | null>(null);
  const [nonce, setNonce] = useState(0);

  const url = `${path}${query(extra)}`;

  useEffect(() => {
    if (!enabled) {
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);

    api
      .get<T>(url)
      .then((next) => {
        if (!cancelled) {
          setData(next);
          setError(null);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setData(null);
          setError(e instanceof Error ? e : new Error("Request failed"));
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
    // filters.bu is part of `url`; listing it keeps the intent explicit for readers.
  }, [url, nonce, enabled, filters.bu]);

  return { data, loading, error, reload: () => setNonce((n) => n + 1), setData };
}
