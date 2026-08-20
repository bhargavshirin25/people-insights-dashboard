"use client";

import { Badge, Button, DataTable, InfoTip, Notice, Panel, SeverityBadge, Skeleton, Td } from "@/components/ui";
import { api, ApiError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { AuditEventRow } from "@/lib/types";
import { useCallback, useEffect, useState } from "react";

interface AnomalyReport {
  userEmail: string;
  role: string;
  deniedAttempts: number;
  targetedBusinessUnits: string[];
  firstAttempt: string;
  lastAttempt: string;
  severity: string;
}

/**
 * The audit trail and the anomalous-access alerts.
 *
 * Retention alone is a record; what makes it a control is being able to see that one user is
 * repeatedly reaching for business units they are not assigned to. Denied events are shown as
 * prominently as granted ones for that reason.
 */
export default function AuditPage() {
  const [events, setEvents] = useState<AuditEventRow[]>([]);
  const [retentionDays, setRetentionDays] = useState<number | null>(null);
  const [anomalies, setAnomalies] = useState<AnomalyReport[]>([]);
  const [threshold, setThreshold] = useState<number | null>(null);
  const [summary, setSummary] = useState<Record<string, Record<string, number>> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [outcomeFilter, setOutcomeFilter] = useState<"ALL" | "GRANTED" | "DENIED">("ALL");

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [recent, anom, sum] = await Promise.all([
        api.get<{ retentionDays: number; count: number; events: AuditEventRow[] }>("/api/audit/recent"),
        api.get<{ threshold: number; anomalies: AnomalyReport[] }>("/api/audit/anomalies"),
        api.get<{ byDataType: Record<string, number>; byOutcome: Record<string, number>; byUser: Record<string, number> }>(
          "/api/audit/summary?hours=24",
        ),
      ]);
      setEvents(recent.events);
      setRetentionDays(recent.retentionDays);
      setAnomalies(anom.anomalies);
      setThreshold(anom.threshold);
      setSummary({ byDataType: sum.byDataType, byOutcome: sum.byOutcome, byUser: sum.byUser });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not load the audit trail.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const shown = events.filter((e) => outcomeFilter === "ALL" || e.outcome === outcomeFilter);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-[19px] leading-tight font-semibold tracking-tight">Audit trail</h1>
          <p className="mt-0.5 text-[11.5px]" style={{ color: "var(--text-secondary)" }}>
            Every data access event: who, which business unit, which data type, and when
            {retentionDays != null && ` · retained ${retentionDays} days by a database TTL index`}
          </p>
        </div>
        <Button onClick={load} disabled={loading}>
          {loading ? "Loading…" : "Refresh"}
        </Button>
      </div>

      {error && (
        <Notice title="Could not load the audit trail" tone="critical">
          {error}
        </Notice>
      )}

      {anomalies.length > 0 && (
        <Panel
          title="Anomalous access alerts"
          subtitle={`Users with ${threshold} or more denied attempts inside the alerting window`}
          info={
            <InfoTip
              label="anomalous access alerts"
              method="Denied access events in the alerting window are grouped by user. A user is alerted on once their denied attempts reach the configured threshold, reported with the window's first and last attempt and the business units they targeted."
              formula="alert when denied attempts by one user in the window ≥ threshold
defaults: threshold 5, window 60 minutes"
              caveat="Counts denials, not intent — a misconfigured BU assignment produces the same pattern as probing. Every denial is written to the audit trail whether or not it reaches the threshold."
            />
          }
        >
          <ul className="space-y-2">
            {anomalies.map((a) => (
              <li key={a.userEmail} className="flex items-start gap-2">
                <SeverityBadge severity={a.severity} />
                <span className="text-[11.5px] leading-snug">
                  <span className="font-medium">{a.userEmail}</span>{" "}
                  <span style={{ color: "var(--text-secondary)" }}>
                    ({a.role}) made {a.deniedAttempts} denied attempts between{" "}
                    {formatDateTime(a.firstAttempt)} and {formatDateTime(a.lastAttempt)}, targeting{" "}
                    {a.targetedBusinessUnits.join(", ")}.
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </Panel>
      )}

      {summary && (
        <div className="grid gap-4 md:grid-cols-3">
          <Panel title="Last 24 hours by outcome">
            <SummaryList data={summary.byOutcome} tone />
          </Panel>
          <Panel title="Last 24 hours by data type">
            <SummaryList data={summary.byDataType} />
          </Panel>
          <Panel title="Last 24 hours by user">
            <SummaryList data={summary.byUser} />
          </Panel>
        </div>
      )}

      <Panel
        title="Recent events"
        subtitle={`${shown.length} of the 500 most recent`}
        info={
          <InfoTip
            label="the recent events table"
            method="The raw audit trail, newest first. Every data read passes through the scope guard and is written here — granted and denied alike — with the caller, the data type, the business unit requested and the outcome."
            formula="one row per access attempt · capped at the 500 most recent"
            caveat="A denial is a record of an attempt, not of data disclosed: the request was refused before any employee data was read."
          />
        }
        actions={
          <div className="flex items-center gap-1">
            {(["ALL", "GRANTED", "DENIED"] as const).map((o) => (
              <button
                key={o}
                type="button"
                onClick={() => setOutcomeFilter(o)}
                className="rounded-md px-2 py-1 text-[11px]"
                style={{
                  background: outcomeFilter === o ? "var(--surface-2)" : "transparent",
                  border: "1px solid var(--border-hairline)",
                  fontWeight: outcomeFilter === o ? 600 : 400,
                }}
              >
                {o === "ALL" ? "All" : o === "GRANTED" ? "Granted" : "Denied"}
              </button>
            ))}
          </div>
        }
      >
        {loading ? (
          <Skeleton rows={6} />
        ) : shown.length === 0 ? (
          <p className="text-[12px]" style={{ color: "var(--text-secondary)" }}>
            No events match this filter.
          </p>
        ) : (
          <DataTable
            headers={["When", "User", "Role", "Business unit", "Data type", "Action", "Outcome", "Detail"]}
            ariaLabel="Audit events"
          >
            {shown.slice(0, 150).map((e) => (
              <tr key={e.id}>
                <Td>
                  <span className="whitespace-nowrap">{formatDateTime(e.at)}</span>
                </Td>
                <Td>{e.userEmail}</Td>
                <Td>{e.role ?? "—"}</Td>
                <Td>{e.businessUnit}</Td>
                <Td>{e.dataType}</Td>
                <Td>
                  <span className="text-[11px]">{e.action}</span>
                </Td>
                <Td>
                  <Badge
                    tone={e.outcome === "DENIED" ? "critical" : "good"}
                    glyph={e.outcome === "DENIED" ? "✕" : "✓"}
                  >
                    {e.outcome}
                  </Badge>
                </Td>
                <Td>
                  <span className="text-[10.5px]" style={{ color: "var(--text-secondary)" }}>
                    {e.detail ?? "—"}
                  </span>
                </Td>
              </tr>
            ))}
          </DataTable>
        )}
      </Panel>
    </div>
  );
}

function SummaryList({ data, tone = false }: { data: Record<string, number>; tone?: boolean }) {
  const entries = Object.entries(data).sort((a, b) => b[1] - a[1]);
  if (entries.length === 0) {
    return (
      <p className="text-[12px]" style={{ color: "var(--text-secondary)" }}>
        No events in the window.
      </p>
    );
  }
  const max = Math.max(...entries.map(([, v]) => v));
  return (
    <ul className="space-y-1.5">
      {entries.map(([key, value]) => (
        <li key={key} className="flex items-center gap-2 text-[11.5px]">
          <span
            aria-hidden="true"
            className="inline-block shrink-0 rounded-sm"
            style={{
              width: Math.max(3, (value / max) * 52),
              height: 8,
              background:
                tone && key === "DENIED"
                  ? "var(--status-critical)"
                  : tone
                    ? "var(--status-good)"
                    : "var(--series-1)",
            }}
          />
          <span className="min-w-0 flex-1 truncate" style={{ color: "var(--text-secondary)" }}>
            {key}
          </span>
          <span className="tnum font-medium">{value}</span>
        </li>
      ))}
    </ul>
  );
}
