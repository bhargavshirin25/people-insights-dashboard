"use client";

import { ChartFrame, CompositionBar, RankedBars, TrendLines } from "@/components/charts";
import { ViewState } from "@/components/ViewState";
import { Badge, DataTable, InfoTip, Panel, Td } from "@/components/ui";
import { SERIES, formatMonth, formatPercent } from "@/lib/format";
import type { ExitView } from "@/lib/types";
import { useViewData } from "@/lib/useViewData";
import { useState } from "react";

/** Exit types in a fixed order, so a type keeps its colour when a filter removes another. */
const EXIT_TYPE_ORDER = [
  "Voluntary - Regrettable",
  "Voluntary - Non-Regrettable",
  "Involuntary",
  "Absconding",
];

const SENTIMENT_TONE: Record<string, "critical" | "warning" | "good" | "neutral"> = {
  Negative: "critical",
  Neutral: "warning",
  Positive: "good",
};

export default function ExitPage() {
  const [theme, setTheme] = useState<string>("");
  const { data, loading, error } = useViewData<ExitView>("/api/insights/exit", {
    theme: theme || undefined,
  });

  return (
    <ViewState loading={loading} error={error}>
      {data && (
        <div className="space-y-4">
          <div>
            <h1 className="text-[19px] leading-tight font-semibold tracking-tight">
              Exit analysis — {data.businessUnit}
            </h1>
            <p className="mt-0.5 text-[11.5px]" style={{ color: "var(--text-secondary)" }}>
              {data.totalExits} exits · {data.voluntaryExits} voluntary · {data.regrettableExits}{" "}
              regrettable · average tenure at exit {data.avgTenureMonths.toFixed(1)} months ·{" "}
              {data.periodLabel}
            </p>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Panel
              title="Top exit themes"
              subtitle="NLP-tagged themes, counted once per exit interview"
              info={
                <InfoTip
                  label="top exit themes"
                  method="Exit interviews are NLP-tagged into themes at ingest. Mentions count each theme once per interview, and the share is against the number of exits in range — not the number of tags."
                  formula="% of exits = theme mentions / total exits in range × 100"
                  caveat="One interview can raise several themes, so the shares sum to well over 100% and the themes are not mutually exclusive."
                />
              }
            >
              {data.topThemes.length === 0 ? (
                <Empty />
              ) : (
                <RankedBars
                  data={data.topThemes.slice(0, 9).map((t) => ({
                    label: t.theme,
                    value: t.mentions,
                    tone:
                      t.sentiment === "Negative"
                        ? "var(--status-critical)"
                        : t.sentiment === "Positive"
                          ? "var(--status-good)"
                          : "var(--status-warning)",
                  }))}
                  maxLabelWidth={180}
                />
              )}
              <p className="mt-2 text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                Bar colour reflects the dominant sentiment for the theme — red negative, amber neutral,
                green positive. Counts are direct-labelled.
              </p>
            </Panel>

            <Panel
              title="Theme sentiment detail"
              info={
                <InfoTip
                  label="theme sentiment detail"
                  method="Within each theme, interviews are split by their sentiment label and expressed as a share of that theme's own mentions. Average score is the mean of the 0–100 sentiment scores attached to that theme."
                  formula="negative % = negative mentions / theme mentions × 100
avg score = Σ theme score / theme mentions"
                  caveat="Percentages are per theme, so each row sums to 100% on its own. A higher score is a more positive sentiment."
                />
              }
            >
              {data.topThemes.length === 0 ? (
                <Empty />
              ) : (
                <DataTable
                  headers={[
                    "Theme",
                    { label: "Mentions", align: "right" },
                    { label: "% of exits", align: "right" },
                    { label: "Negative", align: "right" },
                    { label: "Avg score", align: "right" },
                    "Dominant",
                  ]}
                  ariaLabel="Exit theme sentiment"
                >
                  {data.topThemes.map((t) => (
                    <tr key={t.theme}>
                      <Td>{t.theme}</Td>
                      <Td align="right">{t.mentions}</Td>
                      <Td align="right">{formatPercent(t.pctOfExits)}</Td>
                      <Td align="right">{formatPercent(t.negativePct)}</Td>
                      <Td align="right">{t.avgScore.toFixed(0)}</Td>
                      <Td>
                        <Badge tone={SENTIMENT_TONE[t.sentiment] ?? "neutral"} glyph={t.sentiment === "Negative" ? "▼" : t.sentiment === "Positive" ? "▲" : "▬"}>
                          {t.sentiment}
                        </Badge>
                      </Td>
                    </tr>
                  ))}
                </DataTable>
              )}
            </Panel>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Panel
              title="Exit type breakdown"
              subtitle={`${data.totalExits} exits, of which ${data.regrettableExits} regrettable`}
              info={
                <InfoTip
                  label="the exit type breakdown"
                  method="Exits in range grouped by the exit type recorded on the interview. Segment width is that type's share of all exits in range, and a type keeps its colour as filters change so the bar stays comparable between selections."
                  formula="segment % = exits of that type / total exits in range × 100"
                  caveat="Voluntary and regrettable are separate classifications on the record, not exit types — a regrettable exit is counted once here under its own type."
                />
              }
            >
              {data.exitTypes.length === 0 ? (
                <Empty />
              ) : (
                <CompositionBar
                  height={30}
                  segments={EXIT_TYPE_ORDER.filter((t) => data.exitTypes.some((e) => e.exitType === t)).map(
                    (t) => ({
                      label: t,
                      value: data.exitTypes.find((e) => e.exitType === t)?.count ?? 0,
                      // Colour follows the exit type itself, so a filter that removes one type never
                      // repaints the others.
                      color: SERIES[EXIT_TYPE_ORDER.indexOf(t)],
                    }),
                  )}
                />
              )}
            </Panel>

            <Panel
              title="Tenure band of leavers"
              subtitle="Where in the employee lifecycle exits occur"
              info={
                <InfoTip
                  label="the tenure band of leavers"
                  method="A count of exits in each tenure band as recorded on the exit interview, ordered by length of service rather than by size, so the shape of the lifecycle is readable."
                  formula="bar length = count of exits in the band
share = band count / total exits in range × 100"
                  caveat="Counts leavers, not risk: a large band may simply be where most of the headcount sits. Bands come from the source workbook and are not recomputed from joining dates."
                />
              }
            >
              {data.tenureBands.length === 0 ? (
                <Empty />
              ) : (
                <RankedBars
                  data={data.tenureBands.map((b) => ({ label: b.tenureBand, value: b.count }))}
                  maxLabelWidth={104}
                />
              )}
            </Panel>
          </div>

          <Panel
            title="Sentiment trend across exit themes"
            subtitle="Average NLP sentiment score by exit month, 0–100"
            info={
              <InfoTip
                label="the sentiment trend"
                method="Exits are grouped by their exit month, and each line is the mean sentiment score for one of the five most-mentioned themes in that month. The overall line is the mean of every exit's own overall score for the month."
                formula="point = Σ theme score in month / mentions of that theme in month"
                caveat="Only the top five themes are plotted. A month with few exits swings sharply because the mean is over a handful of interviews, and a theme with no mentions in a month has no point rather than a zero."
              />
            }
            note={
              data.sentimentTrend.length < 2
                ? "A single month is in range. Widen the period filter to see a trend."
                : undefined
            }
          >
            {data.sentimentTrend.length === 0 ? (
              <Empty />
            ) : (
              <ChartFrame
                legend={[{ label: "Overall sentiment", color: SERIES[0] }]}
                height={200}
                table={
                  <DataTable
                    headers={["Month", { label: "Exits", align: "right" }, { label: "Avg sentiment", align: "right" }]}
                    ariaLabel="Exit sentiment by month"
                  >
                    {data.sentimentTrend.map((p) => (
                      <tr key={p.month}>
                        <Td>{formatMonth(p.month)}</Td>
                        <Td align="right">{p.exits}</Td>
                        <Td align="right">{p.avgScore.toFixed(0)}</Td>
                      </tr>
                    ))}
                  </DataTable>
                }
              >
                <TrendLinesWrapper
                  data={data.sentimentTrend.map((p) => ({ label: p.month, overall: p.avgScore }))}
                />
              </ChartFrame>
            )}

            {data.themeTrend.length > 1 && (
              <div className="mt-5">
                <h3 className="mb-2 flex items-center gap-1.5 text-[11.5px] font-semibold">
                  Per-theme sentiment over time
                  <InfoTip
                    label="per-theme sentiment over time"
                    method="One row per exit month, one column per theme. Each cell is the mean sentiment score of the interviews that raised that theme in that month, on a 0–100 scale where higher is more positive."
                    formula="cell = Σ theme score in month / mentions of that theme in month"
                    caveat="Limited to the five most-mentioned themes in range. A blank cell means the theme was not raised that month, which is not the same as a low score."
                  />
                </h3>
                <ThemeTrend themeTrend={data.themeTrend} themes={data.topThemes.slice(0, 5).map((t) => t.theme)} />
              </div>
            )}
          </Panel>

          <Panel
            title="Anonymised exit verbatims"
            subtitle="No name, employee id, team or designation is attached to any quote"
            actions={
              <select
                value={theme}
                onChange={(e) => setTheme(e.target.value)}
                className="rounded-md px-2 py-1 text-[11.5px]"
                style={{ background: "var(--surface-2)", border: "1px solid var(--border-strong)", color: "var(--text-primary)" }}
                aria-label="Filter verbatims by theme"
              >
                <option value="">All themes</option>
                {data.availableThemes.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            }
          >
            {data.verbatims.length === 0 ? (
              <Empty label="No verbatims match this theme for the selected period." />
            ) : (
              <ul className="grid gap-3 md:grid-cols-2">
                {data.verbatims.map((v, i) => (
                  <li
                    key={i}
                    className="rounded-md p-3"
                    style={{ background: "var(--surface-2)", border: "1px solid var(--border-hairline)" }}
                  >
                    <p className="text-[12px] leading-relaxed">&ldquo;{v.quote}&rdquo;</p>
                    <div className="mt-2 flex flex-wrap items-center gap-1.5">
                      {v.theme && <Badge tone="info">{v.theme}</Badge>}
                      {v.sentiment && (
                        <Badge tone={SENTIMENT_TONE[v.sentiment] ?? "neutral"} glyph={v.sentiment === "Negative" ? "▼" : v.sentiment === "Positive" ? "▲" : "▬"}>
                          {v.sentiment}
                        </Badge>
                      )}
                      {v.exitType && <Badge>{v.exitType}</Badge>}
                      {v.tenureBand && <Badge>{v.tenureBand}</Badge>}
                      {v.month && <Badge>{formatMonth(v.month)}</Badge>}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
      )}
    </ViewState>
  );
}

function TrendLinesWrapper({ data }: { data: { label: string; overall: number }[] }) {
  return (
    <TrendLines
      data={data}
      series={[{ key: "overall", label: "Overall sentiment", color: SERIES[0] }]}
      yDomain={[0, 100]}
      height={200}
    />
  );
}

function ThemeTrend({
  themeTrend,
  themes,
}: {
  themeTrend: { month: string; avgScoreByTheme: Record<string, number> }[];
  themes: string[];
}) {
  const data = themeTrend.map((p) => {
    const row: Record<string, string | number | null> = { label: p.month };
    for (const theme of themes) {
      row[theme] = p.avgScoreByTheme[theme] ?? null;
    }
    return row;
  });
  return (
    <ChartFrame
      legend={themes.map((t, i) => ({ label: t, color: SERIES[i] }))}
      height={230}
      caption="Sentiment scores run 0 (most negative) to 100 (most positive). Gaps mean the theme was not raised that month."
    >
      <TrendLinesInner data={data} themes={themes} />
    </ChartFrame>
  );
}

function TrendLinesInner({
  data,
  themes,
}: {
  data: Record<string, string | number | null>[];
  themes: string[];
}) {
  return (
    <TrendLines
      data={data}
      height={230}
      yDomain={[0, 100]}
      series={themes.map((t, i) => ({ key: t, label: t, color: SERIES[i] }))}
    />
  );
}

function Empty({ label = "No exits in scope for this selection." }: { label?: string }) {
  return (
    <p className="text-[12px]" style={{ color: "var(--text-secondary)" }}>
      {label}
    </p>
  );
}
