"use client";

import { ViewState } from "@/components/ViewState";
import { Badge, Button, DataTable, InfoTip, Notice, Panel, Td } from "@/components/ui";
import { api } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import type { RegisterView, RetentionAction } from "@/lib/types";
import { useViewData } from "@/lib/useViewData";
import { Fragment, useState } from "react";

const ACTION_TYPES = [
  "Skip-level conversation",
  "Compensation review",
  "Role change",
  "Manager change",
  "Learning plan",
  "Promotion nomination",
  "Other",
];

/**
 * The attrition risk register.
 *
 * Ranked by score, showing the top three contributing factors per employee, and scoped strictly to
 * the BUs the session is assigned. Each row can carry a logged retention action, which the HR Head
 * sees in their own action log.
 */
export default function RiskPage() {
  const { data, loading, error, reload } = useViewData<RegisterView>("/api/risk/register");
  const [openRow, setOpenRow] = useState<string | null>(null);
  const [bandFilter, setBandFilter] = useState<"ALL" | "High" | "Medium">("ALL");

  const rows = (data?.rows ?? []).filter(
    (r) => bandFilter === "ALL" || r.assessment.band === bandFilter,
  );

  return (
    <ViewState loading={loading} error={error}>
      {data && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h1 className="text-[19px] leading-tight font-semibold tracking-tight">
                Attrition risk register — {data.businessUnit}
              </h1>
              <p className="mt-0.5 text-[11.5px]" style={{ color: "var(--text-secondary)" }}>
                {data.rows.length} of {data.totalActive.toLocaleString("en-IN")} active employees flagged
                at medium or high risk
              </p>
            </div>
            <div className="flex items-center gap-1.5">
              {(["ALL", "High", "Medium"] as const).map((band) => (
                <button
                  key={band}
                  type="button"
                  onClick={() => setBandFilter(band)}
                  className="rounded-md px-2.5 py-1 text-[11.5px]"
                  style={{
                    background: bandFilter === band ? "var(--surface-2)" : "transparent",
                    border: "1px solid var(--border-hairline)",
                    fontWeight: bandFilter === band ? 600 : 400,
                  }}
                >
                  {band === "ALL" ? `All (${data.rows.length})` : band === "High" ? `High (${data.highCount})` : `Medium (${data.mediumCount})`}
                </button>
              ))}
            </div>
          </div>

          <Panel
            title="Risk register"
            info={
              <InfoTip
                label="the risk score"
                method="An additive rule model. Each signal that applies to an employee contributes a fixed weight — PMS trend, eNPS response, promotion history and tenure, attendance against the selection baseline, leave pattern and compa ratio — and the weights are summed and capped at 100. The band follows from the total, and the three heaviest contributing factors are shown per employee."
                formula="score = min(100, Σ weight of every factor that applies)
High ≥ 70 · Medium ≥ 45 · below 45 not listed"
                caveat="Rules, not a trained model: weights are fixed and chosen by HR, so the score ranks and explains rather than predicts a probability. Attendance and leave factors compare against percentiles of the current selection, so the same employee can score differently under a different filter."
              />
            }
            note={data.methodology}
          >
            {rows.length === 0 ? (
              <p className="text-[12px]" style={{ color: "var(--text-secondary)" }}>
                No employees meet the risk threshold for this selection.
              </p>
            ) : (
              <DataTable
                headers={[
                  "Employee",
                  "Grade",
                  "Designation",
                  { label: "Tenure", align: "right" },
                  { label: "Score", align: "right" },
                  "Risk",
                  "Top contributing factors",
                  "Action",
                ]}
                ariaLabel="At-risk employees"
              >
                {rows.map((row) => {
                  const a = row.assessment;
                  const open = openRow === a.employeeId;
                  return (
                    <Fragment key={a.employeeId}>
                      <tr>
                        <Td>
                          <span className="font-medium">{a.fullName ?? a.employeeId}</span>
                          <span className="block text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                            {a.employeeId} · {a.department ?? "—"}
                          </span>
                        </Td>
                        <Td>{a.grade ?? "—"}</Td>
                        <Td>{a.designation ?? "—"}</Td>
                        <Td align="right">{a.tenureYears != null ? `${a.tenureYears}y` : "—"}</Td>
                        <Td align="right">
                          <span className="font-semibold">{a.score}</span>
                        </Td>
                        <Td>
                          <Badge
                            tone={a.band === "High" ? "critical" : "warning"}
                            glyph={a.band === "High" ? "▲" : "◆"}
                          >
                            {a.band}
                          </Badge>
                        </Td>
                        <Td>
                          <ul className="space-y-0.5">
                            {a.factors.map((f) => (
                              <li key={f.code} className="text-[11px] leading-snug">
                                <span className="font-medium">{f.label}</span>
                                <span style={{ color: "var(--text-muted)" }}> · {f.detail}</span>
                              </li>
                            ))}
                          </ul>
                        </Td>
                        <Td>
                          <div className="flex flex-col items-start gap-1">
                            <Button variant="ghost" onClick={() => setOpenRow(open ? null : a.employeeId)}>
                              {open ? "Close" : "Log action"}
                            </Button>
                            {row.loggedActions.length > 0 && (
                              <Badge tone="good" glyph="✓">
                                {row.loggedActions.length} logged
                              </Badge>
                            )}
                          </div>
                        </Td>
                      </tr>
                      {open && (
                        <tr>
                          <Td colSpan={8} className="bg-transparent">
                            <div
                              className="rounded-md p-3"
                              style={{ background: "var(--surface-2)", border: "1px solid var(--border-hairline)" }}
                            >
                              <ActionForm
                                employeeId={a.employeeId}
                                employeeName={a.fullName ?? a.employeeId}
                                existing={row.loggedActions}
                                allFactors={a.allFactors}
                                onLogged={() => {
                                  setOpenRow(null);
                                  reload();
                                }}
                              />
                            </div>
                          </Td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </DataTable>
            )}
          </Panel>
        </div>
      )}
    </ViewState>
  );
}

function ActionForm({
  employeeId,
  employeeName,
  existing,
  allFactors,
  onLogged,
}: {
  employeeId: string;
  employeeName: string;
  existing: RetentionAction[];
  allFactors: { code: string; label: string; detail: string; weight: number }[];
  onLogged: () => void;
}) {
  const [actionType, setActionType] = useState(ACTION_TYPES[0]);
  const [actionDate, setActionDate] = useState("");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/risk/actions/${employeeId}`, {
        actionType,
        actionDate: actionDate || null,
        notes: notes.trim() || null,
      });
      onLogged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not log the action.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <div>
        <h4 className="text-[11.5px] font-semibold">Log a retention action for {employeeName}</h4>
        <p className="mt-0.5 text-[10.5px]" style={{ color: "var(--text-muted)" }}>
          Visible to you and to the HR Head.
        </p>
        <div className="mt-2 flex flex-wrap items-end gap-2">
          <label className="text-[10.5px]" style={{ color: "var(--text-secondary)" }}>
            <span className="block">Action</span>
            <select
              value={actionType}
              onChange={(e) => setActionType(e.target.value)}
              className="mt-0.5 rounded-md px-2 py-1 text-[11.5px]"
              style={{ background: "var(--surface-1)", border: "1px solid var(--border-strong)", color: "var(--text-primary)" }}
            >
              {ACTION_TYPES.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </label>
          <label className="text-[10.5px]" style={{ color: "var(--text-secondary)" }}>
            <span className="block">Date</span>
            <input
              type="date"
              value={actionDate}
              onChange={(e) => setActionDate(e.target.value)}
              className="mt-0.5 rounded-md px-2 py-1 text-[11.5px]"
              style={{ background: "var(--surface-1)", border: "1px solid var(--border-strong)", color: "var(--text-primary)" }}
            />
          </label>
          <Button variant="primary" onClick={submit} disabled={busy}>
            {busy ? "Saving…" : "Log action"}
          </Button>
        </div>
        <input
          type="text"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="Notes (optional)"
          className="mt-2 w-full rounded-md px-2 py-1.5 text-[11.5px]"
          style={{ background: "var(--surface-1)", border: "1px solid var(--border-strong)", color: "var(--text-primary)" }}
        />
        {error && (
          <p className="mt-1.5 text-[11px]" style={{ color: "var(--status-critical-text)" }} role="alert">
            {error}
          </p>
        )}

        {existing.length > 0 && (
          <ul className="mt-3 space-y-1">
            {existing.map((a) => (
              <li key={a.id} className="text-[10.5px]" style={{ color: "var(--text-secondary)" }}>
                <span className="font-medium">{a.actionType}</span> on {formatDate(a.actionDate)} by{" "}
                {a.loggedByName ?? a.loggedByEmail} ({formatDateTime(a.loggedAt)})
                {a.notes && ` — ${a.notes}`}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div>
        <h4 className="text-[11.5px] font-semibold">All contributing factors</h4>
        <ul className="mt-2 space-y-1">
          {allFactors.map((f) => (
            <li key={f.code} className="flex items-start justify-between gap-3 text-[11px]">
              <span>
                <span className="font-medium">{f.label}</span>
                <span className="block text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                  {f.detail}
                </span>
              </span>
              <span className="tnum shrink-0" style={{ color: "var(--text-secondary)" }}>
                +{f.weight}
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
