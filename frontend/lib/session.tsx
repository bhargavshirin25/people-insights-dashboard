"use client";

import { api } from "@/lib/api";
import type { PermissionKey, Session } from "@/lib/types";
import { useRouter } from "next/navigation";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

interface SessionState {
  session: Session | null;
  loading: boolean;
  refresh: () => Promise<Session | null>;
  signOut: () => Promise<void>;
  /**
   * Whether the signed-in user holds a permission.
   *
   * For hiding what the API would refuse anyway — never as the control itself. The server resolves the
   * same set per request, so a screen reached by typing its URL still returns 403.
   */
  can: (permission: PermissionKey) => boolean;
}

const SessionContext = createContext<SessionState | null>(null);

/**
 * Holds the signed-in identity.
 *
 * The server is the authority on role and BU scope; this only mirrors it for rendering. A view
 * hidden here is also refused by the API, so hiding a nav item is a convenience, never the control.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  const refresh = useCallback(async () => {
    try {
      const next = await api.get<Session>("/api/auth/session");
      setSession(next);
      return next;
    } catch {
      setSession(null);
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const signOut = useCallback(async () => {
    try {
      await api.post("/api/auth/logout");
    } finally {
      setSession(null);
      router.replace("/login");
    }
  }, [router]);

  const can = useCallback(
    (permission: PermissionKey) => (session?.permissions ?? []).includes(permission),
    [session],
  );

  const value = useMemo(
    () => ({ session, loading, refresh, signOut, can }),
    [session, loading, refresh, signOut, can],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionState {
  const ctx = useContext(SessionContext);
  if (!ctx) {
    throw new Error("useSession must be used inside SessionProvider");
  }
  return ctx;
}
