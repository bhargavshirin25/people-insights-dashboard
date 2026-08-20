"use client";

import { ChartFrame, ColumnChart, RankedBars, StackedBars } from "@/components/charts";
import { ViewState } from "@/components/ViewState";
import { Badge, DataTable, InfoTip, Notice, Panel, Td } from "@/components/ui";
import { SERIES, formatPercent } from "@/lib/format";
import type { PerformanceView } from "@/lib/types";
import { useViewData } from "@/lib/useViewData";

/** eNPS categories in a fixed order so a category keeps its colour across cycles. */
const ENPS_CATEGORIES = [
  { key: "promoters", label: "Promoters (9–10)", color: SERIES[2] },
  { key: "passives", label: "Passives (7–8)", color: SERIES[3] },
  { key: "detractors", label: "Detractors (0–6)", color: SERIES[1] },
];

export default function PerformancePage() {
  const { data, loading, error } = useViewData<PerformanceView>("/api/insights/performance");

  return (
    <ViewState loading={loading} error={error}>
      {data && (
        <div className="space-y-4">
          <div>
            <h1 className="text-[19px] leading-tight font-semibold tracking-tight">
              Performance &amp; engagement — {data.businessUnit}
            </h1>
            <p className="mt-0.5 text-[11.5px]" style={{ color: "var(--text-secondary)" }}>
              {data.promotions.promotionsLast12Months} promotions in the last 12 months ·{" "}
              {formatPercent(data.promotions.promotionRatePct)} of {data.promotions.eligibleHeadcount.toLocaleString("en-IN")}{" "}
              current headcount
            </p>
          </div>

          <Panel
            title="Rating distribution — latest cycle"
            subtitle={data.pmsTrend.at(-1)?.cycle}
            note={data.promotions.basis}
            info={
              <InfoTip
                label="the rating distribution"
                method="A headcount of employees at each PMS rating from 1 to 5 for the latest appraisal cycle. Every rating band is shown even when empty, so a gap reads as nobody at that rating rather than as missing data."
                formula="bar height = count of employees whose rating for the cycle = r, for r in 1…5"
                caveat="Counts rated employees only — anyone without a rating for the cycle is excluded rather than counted as zero."
              />
            }
          >
            <ColumnChart
              height={210}
              data={Object.entries(data.pmsTrend.at(-1)?.distribution ?? {}).map(([rating, count]) => ({
                label: `Rating ${rating}`,
                value: count,
              }))}
            />
          </Panel>

          <div className="grid gap-4 lg:grid-cols-2">
            <Panel
              title="eNPS by survey cycle"
              subtitle="Promoters minus detractors, as a percentage of respondents"
              info={
                <InfoTip
                  label="eNPS by survey cycle"
                  method="Standard employee net promoter score. Respondents are bucketed by their 0–10 score — promoters 9–10, passives 7–8, detractors 0–6 — and the score is the promoter share minus the detractor share. Passives count towards the base but not the numerator, so a cycle of nothing but passives scores 0."
                  formula="eNPS = (promoters − detractors) / respondents × 100
range −100 to +100"
                  caveat="Derived from each response's numeric score, not from the category label in the source workbook. Employees who did not respond are excluded from the base entirely."
                />
              }
              note={data.enpsTrendNote ?? undefined}
            >
              {data.enpsTrend.length === 0 ? (
                <p className="text-[12px]" style={{ color: "var(--text-secondary)" }}>
                  No eNPS responses in scope.
                </p>
              ) : (
                <>
                  <div className="mb-3 flex flex-wrap gap-4">
                    {data.enpsTrend.map((c) => (
                      <div key={c.cycle}>
                        <div className="text-[10.5px] uppercase tracking-wide" style={{ color: "var(--text-muted)" }}>
                          {c.cycle}
                        </div>
                        <div className="text-[24px] leading-none font-semibold">
                          {c.score >= 0 ? "+" : ""}
                          {c.score.toFixed(0)}
                        </div>
                        <div className="text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                          {c.responses.toLocaleString("en-IN")} responses
                        </div>
                      </div>
                    ))}
                  </div>
                  <ChartFrame
                    legend={ENPS_CATEGORIES.map((c) => ({ label: c.label, color: c.color }))}
                    table={
                      <DataTable
                        headers={[
                          "Cycle",
                          { label: "Promoters", align: "right" },
                          { label: "Passives", align: "right" },
                          { label: "Detractors", align: "right" },
                          { label: "eNPS", align: "right" },
                        ]}
                        ariaLabel="eNPS composition by cycle"
                      >
                        {data.enpsTrend.map((c) => (
                          <tr key={c.cycle}>
                            <Td>{c.cycle}</Td>
                            <Td align="right">{c.promoters}</Td>
                            <Td align="right">{c.passives}</Td>
                            <Td align="right">{c.detractors}</Td>
                            <Td align="right">{c.score.toFixed(0)}</Td>
                          </tr>
                        ))}
                      </DataTable>
                    }
                  >
                    <StackedBars
                      height={170}
                      data={data.enpsTrend.map((c) => ({
                        label: c.cycle,
                        promoters: c.promoters,
                        passives: c.passives,
                        detractors: c.detractors,
                      }))}
                      series={ENPS_CATEGORIES}
                    />
                  </ChartFrame>
                </>
              )}
            </Panel>

            <Panel
              title="Engagement themes"
              subtitle="Derived from eNPS verbatim comments"
              info={
                <InfoTip
                  label="engagement themes"
                  method="Each verbatim comment is keyword-tagged into themes, then the same eNPS formula is applied to the responses carrying each theme. Average score is the plain mean of those responses' 0–10 scores."
                  formula="theme eNPS = (promoters − detractors) / mentions × 100
avg score = Σ score / mentions"
                  caveat="A comment can carry several themes, so mentions sum to more than the respondent count and themes are not mutually exclusive. Responses with no comment contribute to no theme."
                />
              }
              note={data.enpsThemeMethod}
            >
              {data.enpsThemes.length === 0 ? (
                <p className="text-[12px]" style={{ color: "var(--text-secondary)" }}>
                  No comments in scope.
                </p>
              ) : (
                <DataTable
                  headers={[
                    "Theme",
                    { label: "Comments", align: "right" },
                    { label: "Avg score", align: "right" },
                    { label: "Promoters", align: "right" },
                    { label: "Detractors", align: "right" },
                    { label: "Net", align: "right" },
                  ]}
                  ariaLabel="eNPS themes"
                >
                  {data.enpsThemes.slice(0, 11).map((t) => (
                    <tr key={t.theme}>
                      <Td>{t.theme}</Td>
                      <Td align="right">{t.mentions.toLocaleString("en-IN")}</Td>
                      <Td align="right">{t.avgScore.toFixed(1)}</Td>
                      <Td align="right">{t.promoters}</Td>
                      <Td align="right">{t.detractors}</Td>
                      <Td align="right">
                        <span
                          style={{
                            color:
                              t.netSentiment >= 0 ? "var(--status-good-text)" : "var(--status-critical-text)",
                          }}
                        >
                          {t.netSentiment >= 0 ? "+" : ""}
                          {t.netSentiment.toFixed(0)}
                        </span>
                      </Td>
                    </tr>
                  ))}
                </DataTable>
              )}
            </Panel>
          </div>

          <Panel
            title="Declining performance against weak engagement"
            subtitle="Teams where the rating trend is falling and eNPS sits below the selection average"
            info={
              <InfoTip
                label="the declining-performance flag"
                method="Per department, the change in average PMS rating from FY2024-25 to FY2025-26 is compared against that department's own eNPS. A team is flagged only when both conditions hold — ratings are genuinely falling and engagement is below the selection average."
                formula="PMS change = avg rating FY2025-26 − avg rating FY2024-25
flagged when PMS change < −0.1 AND dept eNPS < selection eNPS"
                caveat="The −0.1 margin keeps rounding noise from flagging a flat team. Departments under five people are excluded, both as too small to read and to keep individuals anonymous. A team missing either cycle's ratings cannot be flagged."
              />
            }
            note="A true eNPS drop needs two survey cycles; the extract has one, so the engagement side compares the team's eNPS level against the selection average. The comparison becomes a period-on-period drop automatically once a second cycle is loaded."
          >
            {data.correlation.filter((c) => c.flagged).length === 0 ? (
              <Notice title="No teams flagged" tone="info">
                No team in this selection shows both a falling rating trend and below-average engagement.
              </Notice>
            ) : (
              <DataTable
                headers={[
                  "Team",
                  { label: "Headcount", align: "right" },
                  { label: "Rating change", align: "right" },
                  { label: "Latest avg", align: "right" },
                  { label: "eNPS", align: "right" },
                  "Why flagged",
                ]}
                ariaLabel="Performance and engagement correlation"
              >
                {data.correlation
                  .filter((c) => c.flagged)
                  .map((c) => (
                    <tr key={c.department}>
                      <Td>
                        <span className="inline-flex items-center gap-1.5">
                          <Badge tone="critical" glyph="▲">
                            Attention
                          </Badge>
                          {c.department}
                        </span>
                      </Td>
                      <Td align="right">{c.headcount}</Td>
                      <Td align="right">
                        <span style={{ color: "var(--status-critical-text)" }}>
                          {c.pmsChange != null ? c.pmsChange.toFixed(2) : "—"}
                        </span>
                      </Td>
                      <Td align="right">{c.latestAvgRating?.toFixed(2) ?? "—"}</Td>
                      <Td align="right">{c.enpsScore?.toFixed(0) ?? "—"}</Td>
                      <Td>
                        <span className="text-[11px]" style={{ color: "var(--text-secondary)" }}>
                          {c.reason}
                        </span>
                      </Td>
                    </tr>
                  ))}
              </DataTable>
            )}

            {data.correlation.length > 0 && (
              <div className="mt-4">
                <h3 className="mb-2 flex items-center gap-1.5 text-[11.5px] font-semibold">
                  Rating change by team
                  <InfoTip
                    label="rating change by team"
                    method="Per department, the shift in average PMS rating between the last two appraisal cycles, alongside that department's own eNPS for context. A negative bar means average ratings fell."
                    formula="rating change = avg rating FY2025-26 − avg rating FY2024-25"
                    caveat="Departments under five people are excluded, both as too small to read and to keep individuals anonymous. A department missing either cycle's ratings shows no change rather than a zero."
                  />
                </h3>
                <RankedBars
                  data={data.correlation
                    .slice()
                    .sort((a, b) => (a.pmsChange ?? 0) - (b.pmsChange ?? 0))
                    .slice(0, 10)
                    .map((c) => ({
                      label: c.department,
                      value: Number((c.pmsChange ?? 0).toFixed(2)),
                      tone: (c.pmsChange ?? 0) < 0 ? "var(--status-critical)" : "var(--status-good)",
                    }))}
                  maxLabelWidth={168}
                />
              </div>
            )}
          </Panel>

          <Panel
            title="High performers who have not been promoted"
            subtitle="Three consecutive ratings of 4 or above with no promotion on record — a flight-risk cohort"
            info={
              <InfoTip
                label="the high-performer cohort"
                method="Active employees who scored at or above the high-rating threshold in all three appraisal cycles on record and carry no promotion date. Sorted by tenure, longest first, since time without recognition is what makes the cohort a flight risk."
                formula="rating ≥ 4 in every cycle AND no promotion on record
tenure years = (as-of date − date of joining) / 365.25"
                caveat="Uses the same definition as the risk register and the exported deck, so the three never disagree. An employee with a missing rating in any cycle does not qualify."
              />
            }
          >
            {data.highPerformersNotPromoted.length === 0 ? (
              <p className="text-[12px]" style={{ color: "var(--text-secondary)" }}>
                No employee in this selection has three consecutive high ratings without a promotion.
              </p>
            ) : (
              <DataTable
                headers={[
                  "Employee",
                  "Grade",
                  "Designation",
                  "Team",
                  { label: "Tenure", align: "right" },
                  "Rating history",
                ]}
                ariaLabel="High performers not promoted"
              >
                {data.highPerformersNotPromoted.map((e) => (
                  <tr key={e.employeeId}>
                    <Td>
                      <span className="font-medium">{e.fullName ?? e.employeeId}</span>
                      <span className="block text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                        {e.employeeId}
                      </span>
                    </Td>
                    <Td>{e.grade ?? "—"}</Td>
                    <Td>{e.designation ?? "—"}</Td>
                    <Td>{e.department ?? "—"}</Td>
                    <Td align="right">{e.tenureYears != null ? `${e.tenureYears}y` : "—"}</Td>
                    <Td>
                      <span className="tnum inline-flex gap-1">
                        {e.ratings.map((r, i) => (
                          <span
                            key={i}
                            className="inline-flex h-5 w-5 items-center justify-center rounded text-[10.5px] font-semibold"
                            style={{ background: "color-mix(in srgb, var(--status-good) 18%, transparent)", color: "var(--status-good-text)" }}
                          >
                            {r}
                          </span>
                        ))}
                      </span>
                    </Td>
                  </tr>
                ))}
              </DataTable>
            )}
          </Panel>
        </div>
      )}
    </ViewState>
  );
}
