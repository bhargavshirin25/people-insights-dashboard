"use client";

import { api, filterQuery } from "@/lib/api";
import { useSession } from "@/lib/session";
import type { FilterOptions } from "@/lib/types";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

export interface FilterState {
  bu: string | null;
  grades: string[];
  locations: string[];
  tenureMin: number | null;
  tenureMax: number | null;
  period: string;
  from: string | null;
  to: string | null;
}

export const EMPTY_FILTERS: FilterState = {
  bu: null,
  grades: [],
  locations: [],
  tenureMin: null,
  tenureMax: null,
  period: "LAST_30_DAYS",
  from: null,
  to: null,
};

interface FilterContextValue {
  filters: FilterState;
  options: FilterOptions | null;
  setFilters: (patch: Partial<FilterState>) => void;
  reset: () => void;
  /** Query string for the current filter state, for any view endpoint. */
  query: (extra?: Record<string, string | undefined>) => string;
  activeCount: number;
}

const FilterContext = createContext<FilterContextValue | null>(null);

const LOCAL_KEY = "people-insights.filters";

/**
 * Filter state, shared by every view so all charts and cards move together.
 *
 * State is persisted twice on purpose. localStorage makes a reload instant; the server copy on the
 * user record is what makes the selection survive a different browser, which is the requirement. It
 * is stored per user and never shared, so it cannot become a channel between sessions.
 */
export function FilterProvider({ children }: { children: ReactNode }) {
  const { session } = useSession();
  const [filters, setFiltersState] = useState<FilterState>(EMPTY_FILTERS);
  const [options, setOptions] = useState<FilterOptions | null>(null);
  const [hydrated, setHydrated] = useState(false);

  // Restore: the server copy wins, falling back to this browser's copy.
  useEffect(() => {
    if (!session?.authenticated || hydrated) {
      return;
    }
    let restored: Partial<FilterState> = {};
    if (session.savedFilterJson) {
      try {
        restored = JSON.parse(session.savedFilterJson);
      } catch {
        restored = {};
      }
    } else {
      try {
        const local = window.localStorage.getItem(LOCAL_KEY);
        if (local) restored = JSON.parse(local);
      } catch {
        restored = {};
      }
    }
    setFiltersState({
      ...EMPTY_FILTERS,
      ...restored,
      // The default landing view is the HRBP's own BU, whatever was stored.
      bu: restored.bu ?? session.defaultBusinessUnit ?? null,
    });
    setHydrated(true);
  }, [session, hydrated]);

  useEffect(() => {
    if (!session?.authenticated) return;
    api
      .get<FilterOptions>("/api/dashboard/filter-options")
      .then(setOptions)
      .catch(() => setOptions(null));
  }, [session?.authenticated]);

  const persist = useCallback((next: FilterState) => {
    try {
      window.localStorage.setItem(LOCAL_KEY, JSON.stringify(next));
    } catch {
      // A full or blocked storage quota must not break filtering.
    }
    void api.post("/api/dashboard/saved-filters", { state: JSON.stringify(next) }).catch(() => {
      // Server-side persistence is best-effort; the session still has the state in memory.
    });
  }, []);

  const setFilters = useCallback(
    (patch: Partial<FilterState>) => {
      setFiltersState((current) => {
        const next = { ...current, ...patch };
        persist(next);
        return next;
      });
    },
    [persist],
  );

  const reset = useCallback(() => {
    const next = { ...EMPTY_FILTERS, bu: filters.bu };
    setFiltersState(next);
    persist(next);
  }, [filters.bu, persist]);

  const query = useCallback(
    (extra?: Record<string, string | undefined>) =>
      filterQuery({
        bu: filters.bu,
        grades: filters.grades,
        locations: filters.locations,
        tenureMin: filters.tenureMin,
        tenureMax: filters.tenureMax,
        period: filters.period,
        from: filters.from,
        to: filters.to,
        extra,
      }),
    [filters],
  );

  const activeCount =
    filters.grades.length +
    filters.locations.length +
    (filters.tenureMin != null || filters.tenureMax != null ? 1 : 0) +
    (filters.period !== "LAST_30_DAYS" ? 1 : 0);

  const value = useMemo(
    () => ({ filters, options, setFilters, reset, query, activeCount }),
    [filters, options, setFilters, reset, query, activeCount],
  );

  return <FilterContext.Provider value={value}>{children}</FilterContext.Provider>;
}

export function useFilters(): FilterContextValue {
  const ctx = useContext(FilterContext);
  if (!ctx) {
    throw new Error("useFilters must be used inside FilterProvider");
  }
  return ctx;
}
