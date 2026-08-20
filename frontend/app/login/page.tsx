"use client";

import { api } from "@/lib/api";
import { useSession } from "@/lib/session";
import type { Session } from "@/lib/types";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

interface MicrosoftSignIn {
  /** Present once Entra is configured: where the browser goes to authenticate. */
  redirect?: string;
  placeholder?: boolean;
}

/**
 * Sign-in. Corporate SSO is the only mechanism offered.
 *
 * While no Entra tenant credentials are configured, the button signs the caller in as the placeholder
 * identity the backend is configured with, and the panel below says so rather than letting the screen
 * imply a directory check happened. Once credentials exist the same button starts the real handshake and
 * that panel disappears — the page does not change shape between the two states.
 */
export default function LoginPage() {
  const { session, loading, refresh } = useSession();
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!loading && session?.authenticated) {
      router.replace(session.permissions.includes("VIEW_OVERVIEW") ? "/" : "/access");
    }
  }, [loading, session, router]);

  async function signIn() {
    setBusy(true);
    setError(null);
    try {
      // A GET first, so Spring Security issues the CSRF cookie this POST has to echo.
      await api.get<Session>("/api/auth/session");
      const result = await api.post<MicrosoftSignIn & Session>("/api/auth/microsoft");
      if (result.redirect) {
        window.location.href = result.redirect;
        return;
      }
      const next = await refresh();
      router.replace(next?.permissions.includes("VIEW_OVERVIEW") ? "/" : "/access");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Sign-in failed.");
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-2xl items-center justify-center p-6">
      <div className="w-full">
        <div className="card p-7">
          <p
            className="inline-block rounded border px-2 py-[3px] text-[9.5px] font-semibold tracking-[0.08em] uppercase"
            style={{ color: "var(--status-critical-text)", borderColor: "var(--status-critical)" }}
          >
            Confidential — HR Operations — LeadSquared
          </p>

          <div className="mt-4 flex items-center gap-3">
            <img
              src="/robin.png"
              alt=""
              width={38}
              height={38}
              className="h-[38px] w-[38px] shrink-0 rounded object-contain"
            />
            <div>
              <h1 className="text-[24px] leading-tight font-semibold tracking-tight">Robin Insights</h1>
              <p className="text-[11px]" style={{ color: "var(--text-muted)" }}>
                Powered By LeadSquared
              </p>
            </div>
          </div>

          <p className="mt-3 text-[13px] leading-relaxed" style={{ color: "var(--text-secondary)" }}>
            BU-level people metrics, attrition risk and exit intelligence for HR business partners. Sign
            in with your LeadSquared account to continue.
          </p>

          <button
            type="button"
            onClick={() => void signIn()}
            disabled={busy}
            className="mt-6 inline-flex w-full items-center justify-center gap-2.5 rounded-md px-4 py-2.5 text-[13.5px] font-medium transition-colors hover:opacity-95 disabled:cursor-not-allowed disabled:opacity-60"
            style={{
              background: "var(--chat-launcher-bg)",
              color: "var(--chat-launcher-fg)",
              border: "1px solid var(--chat-launcher-border)",
            }}
          >
            <MicrosoftMark />
            {busy ? "Signing in…" : "Sign in with Microsoft"}
          </button>

          {error && (
            <p
              className="mt-3 text-[11.5px]"
              style={{ color: "var(--status-critical-text)" }}
              role="alert"
            >
              {error}
            </p>
          )}

          <p className="mt-4 text-[11px] leading-relaxed" style={{ color: "var(--text-muted)" }}>
            Every sign-in and every data access on this dashboard is recorded with your identity, the
            business unit and the data type, and retained for 90 days.
          </p>
        </div>

        {session && !session.ssoConfigured && (
          <div
            className="card mt-4 p-4"
            style={{ borderLeftWidth: 3, borderLeftColor: "var(--status-warning)" }}
          >
            <h2 className="text-[12px] font-semibold">Entra is not configured on this instance</h2>
            <p
              className="mt-1 text-[11.5px] leading-relaxed"
              style={{ color: "var(--text-secondary)" }}
            >
              The Entra integration is implemented but has no tenant credentials, so the button above is
              a stand-in: it signs you in as the configured placeholder identity{" "}
              <strong>without checking any credential</strong>, and records the sign-in as a placeholder
              rather than as SSO. Supplying a tenant id, client id and secret and running with the{" "}
              <code>sso</code> profile turns the real handshake on and this stand-in off.
            </p>
          </div>
        )}
      </div>
    </main>
  );
}

/** The Microsoft four-square mark, in its own colours — the one place brand colour is not ours. */
function MicrosoftMark() {
  return (
    <svg viewBox="0 0 21 21" width="16" height="16" aria-hidden="true">
      <rect x="1" y="1" width="9" height="9" fill="#f25022" />
      <rect x="11" y="1" width="9" height="9" fill="#7fba00" />
      <rect x="1" y="11" width="9" height="9" fill="#00a4ef" />
      <rect x="11" y="11" width="9" height="9" fill="#ffb900" />
    </svg>
  );
}
