"use client";

import { Badge } from "@/components/ui";
import { api } from "@/lib/api";
import { useSession } from "@/lib/session";
import type { Session } from "@/lib/types";
import { useRouter } from "next/navigation";
import type { ReactNode } from "react";
import { useEffect, useState } from "react";

interface MicrosoftSignIn {
  /** Present once Entra is configured: where the browser goes to authenticate. */
  redirect?: string;
  placeholder?: boolean;
}

/** What the brand panel is allowed to claim — each line is a real, shipped capability, not marketing. */
const FEATURES: { icon: ReactNode; label: string }[] = [
  {
    icon: <TrendIcon />,
    label: "Real-time metrics across headcount, attrition, performance and engagement",
  },
  {
    icon: <SparkleIcon />,
    label: "AI narratives, checked against your own figures before they're shown",
  },
  {
    icon: <ShieldIcon />,
    label: "Role-based access, scoped by business unit, with a complete audit trail",
  },
];

/**
 * Sign-in. Corporate SSO is the only mechanism offered.
 *
 * While no Entra tenant credentials are configured, the button signs the caller in as the placeholder
 * identity the backend is configured with, and the panel below says so rather than letting the screen
 * imply a directory check happened. Once credentials exist the same button starts the real handshake and
 * that panel disappears — the page does not change shape between the two states.
 *
 * The card is split in two: a brand panel that carries context, three real capabilities and the
 * confidentiality classification, and a sign-in panel that carries exactly one action. Splitting them is
 * what keeps the button the only thing competing for attention on the right, rather than stacking a
 * badge, a paragraph, a button and two disclosures in one column.
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
    <main className="login-backdrop flex min-h-screen items-center justify-center p-4 sm:p-6">
      <div className="login-rise w-full max-w-[940px]">
        <div className="card login-shell overflow-hidden p-0 md:flex md:min-h-[480px]">
          {/* Brand panel — context, three real capabilities and the confidentiality classification
              live here, off to the side of the action rather than stacked above it. Always the dark
              chat-launcher pair, in both themes, the same reasoning as the assistant launcher: a fixed
              identity surface rather than a role in the light/dark ramp. */}
          <div
            className="relative overflow-hidden p-8 md:w-[45%] md:p-10"
            style={{ background: "var(--chat-launcher-bg)" }}
          >
            <FlightPath className="pointer-events-none absolute inset-0 h-full w-full" />

            <div className="relative z-10 flex h-full flex-col gap-8">
              <div className="login-stagger flex items-center gap-3" style={{ animationDelay: "40ms" }}>
                <img
                  src="/robin.png"
                  alt=""
                  width={42}
                  height={42}
                  className="h-[42px] w-[42px] shrink-0 rounded-lg object-contain"
                />
                <div>
                  <p className="text-[21px] leading-tight font-semibold tracking-tight text-white">
                    Robin Insights
                  </p>
                  <p className="text-[11px]" style={{ color: "rgba(255,255,255,0.6)" }}>
                    Powered by LeadSquared
                  </p>
                </div>
              </div>

              <p
                className="login-stagger text-[14px] leading-relaxed font-medium"
                style={{ color: "rgba(255,255,255,0.92)", animationDelay: "110ms" }}
              >
                The people-analytics cockpit for HR business partners — built to replace the
                spreadsheet before every business review.
              </p>

              <ul className="flex flex-col gap-3.5">
                {FEATURES.map((f, i) => (
                  <li
                    key={f.label}
                    className="login-stagger flex items-start gap-3"
                    style={{ animationDelay: `${180 + i * 90}ms` }}
                  >
                    <span
                      className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full"
                      style={{ background: "rgba(255,255,255,0.12)", color: "rgba(255,255,255,0.9)" }}
                      aria-hidden="true"
                    >
                      {f.icon}
                    </span>
                    <span className="text-[12px] leading-snug" style={{ color: "rgba(255,255,255,0.78)" }}>
                      {f.label}
                    </span>
                  </li>
                ))}
              </ul>

              <span
                className="login-stagger mt-auto inline-flex w-fit items-center gap-1.5 rounded-full px-2.5 py-1 text-[9.5px] font-semibold tracking-[0.08em] uppercase"
                style={{
                  background: "rgba(255,255,255,0.12)",
                  color: "rgba(255,255,255,0.8)",
                  animationDelay: "450ms",
                }}
              >
                Confidential · HR Operations
              </span>
            </div>
          </div>

          {/* Sign-in panel — one action, and only what a reader needs to trust and complete it. */}
          <div className="flex flex-1 flex-col justify-center gap-5 p-8 md:p-11">
            <div>
              <h1 className="text-[21px] leading-tight font-semibold tracking-tight">Welcome back</h1>
              <p className="mt-1.5 text-[12.5px] leading-relaxed" style={{ color: "var(--text-secondary)" }}>
                Sign in with your LeadSquared account to open your dashboard.
              </p>
            </div>

            <button
              type="button"
              onClick={() => void signIn()}
              disabled={busy}
              className="login-button inline-flex w-full items-center justify-center gap-2.5 rounded-md px-4 py-3 text-[13.5px] font-medium transition-all disabled:cursor-not-allowed disabled:opacity-60"
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
              <p className="text-[11.5px]" style={{ color: "var(--status-critical-text)" }} role="alert">
                {error}
              </p>
            )}

            {session && !session.ssoConfigured && (
              <p className="text-[10.5px] leading-relaxed" style={{ color: "var(--text-muted)" }}>
                <Badge tone="warning" glyph="◆">
                  Placeholder sign-in
                </Badge>{" "}
                Entra SSO isn&apos;t configured on this instance, so this signs you in as a fixed
                identity with no credential check — fine for this demo, but it must not be used with
                real employee data.
              </p>
            )}

            <p className="text-[10.5px] leading-relaxed" style={{ color: "var(--text-muted)" }}>
              Every sign-in and every data access on this dashboard is recorded with your identity, the
              business unit and the data type, and retained for 90 days.
            </p>
          </div>
        </div>
      </div>
    </main>
  );
}

/**
 * A quiet ascending route across the brand panel — a flight path and a trend line at once, which is
 * the one idea this page is allowed to spend its attention on. Dotted rather than solid, so it reads
 * as a route rather than a stray line; each waypoint is plainer than the last, dot on the same style,
 * so the endpoint reads as "the destination" through weight alone rather than a glow effect.
 */
function FlightPath({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 460 600" preserveAspectRatio="none" aria-hidden="true" className={className}>
      <path
        d="M 40 560 Q 140 480 210 380 T 362 56"
        fill="none"
        stroke="rgba(255,255,255,0.16)"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeDasharray="1 9"
      />
      <circle cx="40" cy="560" r="3" fill="rgba(255,255,255,0.3)" />
      <circle cx="210" cy="380" r="3.5" fill="rgba(255,255,255,0.42)" />
      <circle cx="286" cy="218" r="3" fill="rgba(255,255,255,0.36)" />
      <circle cx="362" cy="56" r="4.5" fill="rgba(255,255,255,0.92)" />
    </svg>
  );
}

/** Ascending metrics — the same idea as the flight path, in miniature. */
function TrendIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M4 20V14" />
      <path d="M11.5 20V9" />
      <path d="M19 20V4" />
    </svg>
  );
}

/** The same twinkling mark the AI summary carries in the product — reused, not reinvented. */
function SparkleIcon() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 16 16"
      fill="currentColor"
      aria-hidden="true"
      className="ai-sparkle"
    >
      <path
        className="ai-sparkle-lg"
        d="M6.5 1 7.68 5.12 11.8 6.3 7.68 7.48 6.5 11.6 5.32 7.48 1.2 6.3 5.32 5.12Z"
      />
    </svg>
  );
}

/** A verified shield — access control and the audit trail, not a generic lock. */
function ShieldIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M12 3.4 19 6.2v4.8c0 5-3 8.1-7 9.6-4-1.5-7-4.6-7-9.6V6.2Z" />
      <path d="M9 12.2l2 2 4-4.4" />
    </svg>
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
