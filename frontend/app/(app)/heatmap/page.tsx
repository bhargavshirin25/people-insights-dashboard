"use client";

import { ViewState } from "@/components/ViewState";
import { DataTable, InfoTip, Panel, Td } from "@/components/ui";
import { formatDate, formatPercent, sequentialStep } from "@/lib/format";
import type { HeatMapView } from "@/lib/types";
import { useViewData } from "@/lib/useViewData";

type Row = HeatMapView["rows"][number];

interface Column {
  key: string;
  label: string;
  value: (r: Row) => number | null;
  format: (v: number | null) => string;
  /** True when a larger value is the worse outcome, which flips the ramp direction. */
  higherIsWorse: boolean;
}

const COLUMNS: Column[] = [
  { key: "headcount", label: "Headcount", value: (r) => r.headcount, format: (v) => (v == null ? "—" : v.toLocaleString("en-IN")), higherIsWorse: false },
  { key: "attritionRolling3m", label: "Attrition 3m", value: (r) => r.attritionRolling3m, format: (v) => formatPercent(v), higherIsWorse: true },
  { key: "attritionYtd", label: "Attrition YTD", value: (r) => r.attritionYtd, format: (v) => formatPercent(v), higherIsWorse: true },
  { key: "enps", label: "eNPS", value: (r) => r.enps ?? null, format: (v) => (v == null ? "—" : `${v >= 0 ? "+" : ""}${v.toFixed(0)}`), higherIsWorse: false },
  { key: "atRiskPct", label: "At risk", value: (r) => r.atRiskPct, format: (v) => formatPercent(v), higherIsWorse: true },
  { key: "attendanceRatePct", label: "Attendance", value: (r) => r.attendanceRatePct, format: (v) => formatPercent(v), higherIsWorse: false },
];

/**
 * The org-wide heat map: the same headline metrics, one row per BU.
 *
 * Each column is its own one-hue sequential scale, shaded within that column's own range, because a
 * shared scale across headcount and percentages would be meaningless. Every cell also carries its
 * number, so the shading is a reading aid and never the only encoding.
 */
export default function HeatMapPage() {
  const { data, loading, error } = useViewData<HeatMapView>("/api/dashboard/heatmap");

  return (
    <ViewState loading={loading} error={error}>
      {data && (
        <div className="space-y-4">
          <div>
            <h1 className="text-[19px] leading-tight font-semibold tracking-tight">
              Org-wide people metrics heat map
            </h1>
            <p className="mt-0.5 text-[11.5px]" style={{ color: "var(--text-secondary)" }}>
              {data.rows.length} business units · reporting date {formatDate(data.asOf)} · ranked by
              rolling 3-month voluntary attrition
            </p>
          </div>

          <Panel
            info={
              <InfoTip
                label="the org heat map"
                method="Every BU is built through the same code path as its own dashboard, so a figure here always matches that BU's view. Shading is scaled within each column's own range — the darkest step is the worst value in that column, not a fixed threshold."
                formula="attrition % = voluntary exits / avg headcount × (12 / months) × 100
at-risk % = employees on the risk register / active headcount × 100
eNPS = (promoters − detractors) / respondents × 100"
                caveat="Colour is comparative within a column, so shading cannot be read across columns, and a dark cell in a narrow range may not be a bad absolute number. Every value is printed, so nothing depends on colour alone."
              />
            }
            note="Shading runs light to dark within each column's own range, with the darkest step marking the worst outcome for that metric. Numbers are always shown, so no value depends on colour."
          >
            <div className="scroll-x">
              <table className="w-full text-[12px]" aria-label="Business unit metrics heat map">
                <thead>
                  <tr className="hairline-b">
                    <th
                      scope="col"
                      className="px-2 py-2 text-left text-[10.5px] font-semibold uppercase tracking-wide"
                      style={{ color: "var(--text-muted)" }}
                    >
                      Business unit
                    </th>
                    {COLUMNS.map((c) => (
                      <th
                        key={c.key}
                        scope="col"
                        className="px-2 py-2 text-right text-[10.5px] font-semibold uppercase tracking-wide"
                        style={{ color: "var(--text-muted)" }}
                      >
                        {c.label}
                      </th>
                    ))}
                    <th
                      scope="col"
                      className="px-2 py-2 text-right text-[10.5px] font-semibold uppercase tracking-wide"
                      style={{ color: "var(--text-muted)" }}
                    >
                      Exits in period
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={row.businessUnit}>
                      <td
                        className="px-2 py-2 font-medium whitespace-nowrap"
                        style={{ borderTop: "1px solid var(--border-hairline)" }}
                      >
                        {row.businessUnit}
                      </td>
                      {COLUMNS.map((c) => {
                        const values = data.rows.map((r) => c.value(r)).filter((v): v is number => v != null);
                        const min = Math.min(...values);
                        const max = Math.max(...values);
                        const v = c.value(row);
                        // Flip the ramp so the darkest step always means "worst".
                        const step =
                          v == null
                            ? null
                            : c.higherIsWorse
                              ? sequentialStep(v, min, max)
                              : sequentialStep(max - v + min, min, max);
                        return (
                          <td
                            key={c.key}
                            className="px-1 py-1"
                            style={{ borderTop: "1px solid var(--border-hairline)" }}
                          >
                            <div
                              className="tnum rounded px-2 py-1.5 text-right font-medium"
                              style={{
                                background: step?.bg ?? "transparent",
                                color: step?.dark ? "#ffffff" : "var(--text-primary)",
                              }}
                            >
                              {c.format(v)}
                            </div>
                          </td>
                        );
                      })}
                      <td
                        className="tnum px-2 py-2 text-right"
                        style={{ borderTop: "1px solid var(--border-hairline)" }}
                      >
                        {row.exitsInPeriod}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>

          <Panel
            title="Table view"
            subtitle="The same figures without shading"
            info={
              <InfoTip
                label="the table view"
                method="The same values as the heat map above, unshaded. Each BU is computed through that BU's own dashboard code path, so a figure here matches what the HRBP for that BU sees."
                formula="attrition % = voluntary exits / avg headcount × (12 / months) × 100
at-risk % = risk register count / active headcount × 100
attendance % = present + ½ · half days / working days × 100"
                caveat="Rows are ordered by rolling three-month attrition, worst first. Exits in period follows the period filter; the attendance and at-risk columns are computed over their own windows."
              />
            }
          >
            <DataTable
              headers={[
                "Business unit",
                { label: "Headcount", align: "right" },
                { label: "Attrition 3m", align: "right" },
                { label: "Attrition YTD", align: "right" },
                { label: "eNPS", align: "right" },
                { label: "At-risk", align: "right" },
                { label: "At-risk %", align: "right" },
                { label: "Attendance", align: "right" },
                { label: "Exits", align: "right" },
              ]}
              ariaLabel="Business unit metrics"
            >
              {data.rows.map((r) => (
                <tr key={r.businessUnit}>
                  <Td>{r.businessUnit}</Td>
                  <Td align="right">{r.headcount.toLocaleString("en-IN")}</Td>
                  <Td align="right">{formatPercent(r.attritionRolling3m)}</Td>
                  <Td align="right">{formatPercent(r.attritionYtd)}</Td>
                  <Td align="right">{r.enps == null ? "—" : r.enps.toFixed(0)}</Td>
                  <Td align="right">{r.atRiskCount}</Td>
                  <Td align="right">{formatPercent(r.atRiskPct)}</Td>
                  <Td align="right">{formatPercent(r.attendanceRatePct)}</Td>
                  <Td align="right">{r.exitsInPeriod}</Td>
                </tr>
              ))}
            </DataTable>
          </Panel>
        </div>
      )}
    </ViewState>
  );
}
