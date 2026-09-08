"use client";

import { Skeleton } from "@/components/ui";
import { ApiError } from "@/lib/api";
import type { ReactNode } from "react";

/**
 * Renders the loading and error states every view shares.
 *
 * An access denial is presented as a distinct, explicit state rather than an empty view. That is the
 * point of the backend answering 403 instead of returning no rows: the person sees that the data
 * exists and they are not permitted it, and the attempt is in the audit trail either way.
 *
 * These states render as the page's entire content — usually the only thing on an otherwise-empty
 * screen — so they are built as a centred empty state (an icon, a heading, a short explanation) rather
 * than a thin coloured-border banner, which read as a leftover fragment sitting in a lot of blank
 * space rather than something designed for the moment.
 */
export function ViewState({
  loading,
  error,
  children,
}: {
  loading: boolean;
  error: Error | ApiError | null;
  children: ReactNode;
}) {
  if (error) {
    if (error instanceof ApiError && error.isAccessDenied) {
      return (
        <PageNotice tone="critical" icon={<LockIcon />} title="Access denied">
          <p>{error.message}</p>
          {error.requestedBusinessUnit && error.requestedBusinessUnit !== "null" && (
            <p className="mt-1.5">
              Requested business unit: <strong>{error.requestedBusinessUnit}</strong>. This attempt has
              been recorded in the audit trail.
            </p>
          )}
        </PageNotice>
      );
    }
    if (error instanceof ApiError && error.isUnauthenticated) {
      return (
        <PageNotice tone="warning" icon={<ClockIcon />} title="Session expired">
          Your session has timed out. Sign in again to continue.
        </PageNotice>
      );
    }
    return (
      <PageNotice tone="critical" icon={<AlertIcon />} title="Could not load this view">
        {error.message}
      </PageNotice>
    );
  }

  if (loading) {
    return (
      <div className="card p-4">
        <Skeleton rows={5} />
      </div>
    );
  }

  return <>{children}</>;
}

const TONE: Record<"critical" | "warning", { bg: string; fg: string }> = {
  critical: {
    bg: "color-mix(in srgb, var(--status-critical) 14%, transparent)",
    fg: "var(--status-critical-text)",
  },
  warning: {
    bg: "color-mix(in srgb, var(--status-warning) 20%, transparent)",
    fg: "var(--status-warning-text)",
  },
};

function PageNotice({
  tone,
  icon,
  title,
  children,
}: {
  tone: "critical" | "warning";
  icon: ReactNode;
  title: string;
  children?: ReactNode;
}) {
  const t = TONE[tone];
  return (
    <div
      className="card flex flex-col items-center gap-3 px-6 py-16 text-center"
      role={tone === "critical" ? "alert" : undefined}
    >
      <span
        className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full"
        style={{ background: t.bg, color: t.fg }}
        aria-hidden="true"
      >
        {icon}
      </span>
      <h2 className="text-[14px] font-semibold">{title}</h2>
      {children && (
        <div className="max-w-md text-[12px] leading-relaxed" style={{ color: "var(--text-secondary)" }}>
          {children}
        </div>
      )}
    </div>
  );
}

function LockIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <rect x="5" y="11" width="14" height="9" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  );
}

function ClockIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V12l3 2" />
    </svg>
  );
}

function AlertIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M12 3.5 21 19H3z" />
      <path d="M12 9.5v4" />
      <circle cx="12" cy="16.5" r="0.7" fill="currentColor" stroke="none" />
    </svg>
  );
}
