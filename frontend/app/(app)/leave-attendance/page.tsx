"use client";

import { CompositionBar } from "@/components/charts";
import { ViewState } from "@/components/ViewState";
import { Badge, DataTable, InfoTip, Notice, Panel, SeverityBadge, Td } from "@/components/ui";
import { SERIES, formatMonth, formatNumber, formatPercent } from "@/lib/format";
import type { LeaveAttendanceView } from "@/lib/types";
import { useViewData } from "@/lib/useViewData";

/** Attendance states in a fixed order so a state keeps its colour as teams are filtered. */
const STATE_SERIES = [
  { key: "presentRatePct", label: "Present", color: SERIES[2] },
  { key: "halfDayRatePct", label: "Half day", color: SERIES[4] },
  { key: "leaveRatePct", label: "Approved leave", color: SERIES[0] },
  { key: "absentRatePct", label: "Absent", color: SERIES[3] },
  { key: "lopRatePct", label: "Loss of pay", color: SERIES[1] },
];

export default function LeaveAttendancePage() {
  const { data, loading, error } = useViewData<LeaveAttendanceView>("/api/insights/leave-attendance");

  return (
    <ViewState loading={loading} error={error}>
      {data && (
        <div className="space-y-4">
          <div>
            <h1 className="text-[19px] leading-tight font-semibold tracking-tight">
              Leave &amp; attendance health — {data.businessUnit}
            </h1>
            <p className="mt-0.5 text-[11.5px]" style={{ color: "var(--text-secondary)" }}>
              Attendance register covers {data.monthsCovered.length > 0
                ? `${formatMonth(data.monthsCovered[0])} to ${formatMonth(data.monthsCovered.at(-1)!)}`
                : "no months"} · {formatNumber(data.lopDaysTotal)} loss-of-pay days recorded
            </p>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Panel
              title="Leave utilisation by type"
              subtitle="FY 2025-26 entitlement against days taken"
              info={
                <InfoTip
                  label="leave utilisation by type"
                  method="Entitlement, days taken and lapsed days are summed across every employee in the selection, per leave type. Utilisation is the summed days taken against the summed entitlement — a pooled rate, not the average of each person's rate."
                  formula="utilisation % = Σ taken / Σ entitlement × 100"
                  caveat="Paternity and maternity appear only when the selection carries entitlement for them. Loss of pay is not a leave type and is excluded here — it comes from the attendance register."
                />
              }
            >
              {data.leaveUtilisation.length === 0 ? (
                <Empty />
              ) : (
                <DataTable
                  headers={[
                    "Leave type",
                    { label: "Entitlement", align: "right" },
                    { label: "Taken", align: "right" },
                    { label: "Utilisation", align: "right" },
                    { label: "Lapsed", align: "right" },
                  ]}
                  ariaLabel="Leave utilisation by type"
                >
                  {data.leaveUtilisation.map((l) => (
                    <tr key={l.leaveType}>
                      <Td>{l.leaveType}</Td>
                      <Td align="right">{formatNumber(l.entitlement)}</Td>
                      <Td align="right">{formatNumber(l.taken)}</Td>
                      <Td align="right">
                        <span className="inline-flex items-center gap-1.5">
                          <span
                            aria-hidden="true"
                            className="inline-block rounded-sm"
                            style={{
                              width: Math.max(3, Math.min(56, l.utilisationPct * 0.56)),
                              height: 8,
                              background: SERIES[0],
                            }}
                          />
                          {formatPercent(l.utilisationPct)}
                        </span>
                      </Td>
                      <Td align="right">{formatNumber(l.lapsed)}</Td>
                    </tr>
                  ))}
                </DataTable>
              )}
              <p className="mt-2 text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                Loss of pay is an attendance state rather than a leave balance, so it is counted from the
                attendance register and reported separately above.
              </p>
            </Panel>

            <Panel
              title="Absenteeism anomaly flags"
              subtitle="Teams whose absence or loss-of-pay rate sits well above the selection average"
              info={
                <InfoTip
                  label="absenteeism anomaly flags"
                  method="For absence rate and loss-of-pay rate separately, the mean and standard deviation are taken across the teams in the current selection. A team is flagged when it sits at least 1.5 standard deviations above that mean, so the threshold adapts to the selection instead of being a fixed percentage."
                  formula="σ from mean = (team rate − selection mean) / SD
flag when ≥ 1.5 · High ≥ 2.5 · Medium ≥ 2.0 · Low ≥ 1.5"
                  caveat="Needs at least three teams in the selection to compute a distribution. Only teams above the mean are flagged, never below."
                />
              }
            >
              {data.anomalies.length === 0 ? (
                <Notice title="No anomalies above threshold" tone="info">
                  No team in this selection exceeds 1.5 standard deviations above the mean on either
                  absence or loss-of-pay rate.
                </Notice>
              ) : (
                <ul className="space-y-2">
                  {data.anomalies.map((a, i) => (
                    <li key={i} className="flex items-start gap-2">
                      <SeverityBadge severity={a.severity} />
                      <span className="text-[11.5px] leading-snug">
                        <span className="font-medium">{a.team}</span>
                        <span style={{ color: "var(--text-secondary)" }}> — {a.detail}</span>
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>

          <Panel
            title="Attendance by team"
            subtitle="Aggregated, never per individual. Teams with fewer than five people are suppressed."
            info={
              <InfoTip
                label="attendance by team"
                method="Day counts are summed across every employee-month in the team, then divided by summed working days. The state columns are plain shares of working days and sum to 100%. The health column is a composite index on a 0–100 scale — present days in full, half days at half weight, less the loss-of-pay rate."
                formula="state % = Σ days in state / Σ working days × 100
health = max(0, present % + ½ · half-day % − LOP %)"
                caveat="Health is an index, not a rate: loss of pay counts twice by design, excluded from present then subtracted again. Aggregated over every month in the extract, so the period filter does not narrow it."
              />
            }
          >
            {data.teamHealth.length === 0 ? (
              <Empty />
            ) : (
              <>
                <h3 className="mb-2 flex items-center gap-1.5 text-[11.5px] font-semibold">
                  Attendance state mix by team
                  <InfoTip
                    label="the attendance state mix"
                    method="Each bar splits one team's working days into the five recorded states, as a share of that team's summed working days. The five states partition working days exactly, so every bar totals 100%."
                    formula="segment % = Σ days in state / Σ working days × 100
states: present · half day · approved leave · absent · LOP"
                    caveat="Half days are shown separately rather than folded into present, so the bar reconciles. Working days exclude week offs."
                  />
                </h3>
                <div className="mb-2 flex flex-wrap gap-x-4 gap-y-1">
                  {STATE_SERIES.map((st) => (
                    <span key={st.key} className="inline-flex items-center gap-1.5 text-[11px]" style={{ color: "var(--text-secondary)" }}>
                      <span aria-hidden="true" className="inline-block rounded-sm" style={{ width: 9, height: 9, background: st.color }} />
                      {st.label}
                    </span>
                  ))}
                </div>
                <ul className="space-y-2">
                  {data.teamHealth.slice(0, 12).map((t) => (
                    <li key={t.team} className="grid items-center gap-3" style={{ gridTemplateColumns: "minmax(120px, 190px) 1fr" }}>
                      <span className="truncate text-[11.5px]" title={t.team}>
                        {t.team}
                        <span className="ml-1" style={{ color: "var(--text-muted)" }}>({t.headcount})</span>
                      </span>
                      <CompositionBar
                        height={16}
                        showLabels={false}
                        valueSuffix="%"
                        segments={STATE_SERIES.map((st) => ({
                          label: st.label,
                          value: t[st.key as keyof typeof t] as number,
                          color: st.color,
                        }))}
                      />
                    </li>
                  ))}
                </ul>

                <div className="mt-4">
                  <DataTable
                    headers={[
                      "Team",
                      { label: "Headcount", align: "right" },
                      { label: "Health", align: "right" },
                      { label: "Present", align: "right" },
                      { label: "Half day", align: "right" },
                      { label: "Leave", align: "right" },
                      { label: "Absent", align: "right" },
                      { label: "LOP", align: "right" },
                      { label: "Single punch", align: "right" },
                      { label: "Regularisations", align: "right" },
                    ]}
                    ariaLabel="Attendance by team"
                  >
                    {data.teamHealth.map((t) => (
                      <tr key={t.team}>
                        <Td>{t.team}</Td>
                        <Td align="right">{t.headcount}</Td>
                        <Td align="right">{t.attendanceHealthScore.toFixed(1)}</Td>
                        <Td align="right">{formatPercent(t.presentRatePct)}</Td>
                        <Td align="right">{formatPercent(t.halfDayRatePct)}</Td>
                        <Td align="right">{formatPercent(t.leaveRatePct)}</Td>
                        <Td align="right">{formatPercent(t.absentRatePct)}</Td>
                        <Td align="right">{formatPercent(t.lopRatePct)}</Td>
                        <Td align="right">{t.singlePunchDays}</Td>
                        <Td align="right">{t.regularisationRequests}</Td>
                      </tr>
                    ))}
                  </DataTable>
                </div>
              </>
            )}
          </Panel>

          {!data.individualListsVisible ? (
            <Notice title="Individual lists withheld" tone="warning">
              {data.individualListsNote}
            </Notice>
          ) : (
            <div className="space-y-4">
              <Panel
                title="Zero leave taken in the last 6 months"
                subtitle="A burnout risk signal"
                info={
                  <InfoTip
                    label="the zero-leave list"
                    method="Active employees with no approved leave day recorded in the attendance register across the six months ending at the reporting date. Sorted by unused earned leave, highest first, so the largest untaken balances surface first."
                    formula="Σ leave days over the last 6 months = 0
sorted by EL closing balance, descending"
                    caveat="Employees with no attendance rows in the window are skipped, not treated as zero. Counts approved leave only — an absence or loss-of-pay day does not disqualify someone from this list."
                  />
                }
                note={data.zeroLeaveNote ?? undefined}
              >
                {data.zeroLeaveEmployees.length === 0 ? (
                  <Empty label="Nobody in this selection recorded zero leave over six months." />
                ) : (
                  <EmployeeList rows={data.zeroLeaveEmployees} valueLabel="EL unused" />
                )}
              </Panel>

              <Panel
                title="Excessive unplanned absence in the last 3 months"
                info={
                  <InfoTip
                    label="the unplanned absence list"
                    method="Absent and loss-of-pay days are summed per employee over the three months ending at the reporting date, and anyone above the 90th percentile for the current selection is listed. The bar moves with the selection rather than being a fixed day count."
                    formula="unplanned days = Σ absent + Σ LOP over the last 3 months
listed when unplanned days > 90th percentile of the selection"
                    caveat="Needs at least 20 employees with attendance in the window to establish a percentile; below that the list is empty rather than approximate. By construction roughly the top tenth of the selection appears here."
                  />
                }
                note={data.unplannedLeaveNote ?? undefined}
              >
                {data.excessiveUnplannedLeave.length === 0 ? (
                  <Empty label="No employee exceeds the selection's 90th percentile for unplanned absence." />
                ) : (
                  <EmployeeList rows={data.excessiveUnplannedLeave} valueLabel="Days" />
                )}
              </Panel>
            </div>
          )}
        </div>
      )}
    </ViewState>
  );
}

function EmployeeList({
  rows,
  valueLabel,
}: {
  rows: LeaveAttendanceView["zeroLeaveEmployees"];
  valueLabel: string;
}) {
  return (
    <>
      <p className="mb-2 text-[11px]" style={{ color: "var(--text-secondary)" }}>
        <Badge tone="warning" glyph="◆">
          {rows.length} employees
        </Badge>
      </p>
      <DataTable
        headers={["Employee", "Grade", "Team", { label: valueLabel, align: "right" }, "Detail"]}
        ariaLabel={valueLabel}
      >
        {rows.slice(0, 25).map((r) => (
          <tr key={r.employeeId}>
            <Td>
              <span className="font-medium">{r.fullName ?? r.employeeId}</span>
              <span className="block text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                {r.employeeId}
              </span>
            </Td>
            <Td>{r.grade ?? "—"}</Td>
            <Td>{r.department ?? "—"}</Td>
            <Td align="right">{r.value != null ? formatNumber(r.value) : "—"}</Td>
            <Td>
              <span className="text-[10.5px]" style={{ color: "var(--text-secondary)" }}>
                {r.detail}
              </span>
            </Td>
          </tr>
        ))}
      </DataTable>
      {rows.length > 25 && (
        <p className="mt-2 text-[10.5px]" style={{ color: "var(--text-muted)" }}>
          Showing the 25 highest of {rows.length}.
        </p>
      )}
    </>
  );
}

function Empty({ label = "No data in scope for this selection." }: { label?: string }) {
  return (
    <p className="text-[12px]" style={{ color: "var(--text-secondary)" }}>
      {label}
    </p>
  );
}
