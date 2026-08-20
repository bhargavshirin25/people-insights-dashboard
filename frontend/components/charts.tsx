"use client";

import { SERIES, formatMonth } from "@/lib/format";
import type { ReactNode } from "react";
import { useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  LabelList,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

/*
 * Chart primitives.
 *
 * Shared conventions, applied everywhere rather than per chart: one y-axis only (never a second
 * scale), recessive gridlines and axes, 2px lines with 8px markers, 4px rounded data-ends, a 2px
 * surface-coloured gap between adjacent and stacked fills, a tooltip on every plot, and a legend
 * whenever two or more series are present. Charts with two or more series also offer a table view,
 * which doubles as the relief for the light-mode series colours that sit below 3:1 on white.
 */

const AXIS_TICK = { fontSize: 10.5, fill: "var(--text-muted)" };
const GRID = "var(--gridline)";
const AXIS_LINE = { stroke: "var(--axis)" };

/**
 * Legend, table-view toggle and caption around a chart.
 *
 * Layout only — each chart component owns its own responsive container and height, so this must not
 * introduce a second one.
 */
export function ChartFrame({
  legend,
  children,
  table,
  caption,
}: {
  legend?: { label: string; color: string }[];
  children: ReactNode;
  table?: ReactNode;
  /** Accepted and ignored: the chart child sets its own height. */
  height?: number;
  caption?: ReactNode;
}) {
  const [showTable, setShowTable] = useState(false);
  return (
    <div>
      {(legend?.length ?? 0) > 0 && (
        <div className="mb-2 flex flex-wrap items-center gap-x-3 gap-y-1">
          {legend!.map((l) => (
            <span key={l.label} className="inline-flex items-center gap-1.5 text-[11px]" style={{ color: "var(--text-secondary)" }}>
              <span
                aria-hidden="true"
                className="inline-block rounded-sm"
                style={{ width: 9, height: 9, background: l.color }}
              />
              {l.label}
            </span>
          ))}
          {table && (
            <button
              type="button"
              onClick={() => setShowTable((v) => !v)}
              className="ml-auto text-[10.5px] underline underline-offset-2"
              style={{ color: "var(--text-muted)" }}
            >
              {showTable ? "Show chart" : "Show table"}
            </button>
          )}
        </div>
      )}
      {showTable && table ? table : children}
      {caption && (
        <p className="mt-2 text-[10.5px] leading-relaxed" style={{ color: "var(--text-muted)" }}>
          {caption}
        </p>
      )}
    </div>
  );
}

function ChartTooltip({
  active,
  payload,
  label,
  labelFormatter,
  valueSuffix = "",
}: {
  active?: boolean;
  payload?: { name?: string; value?: number | string; color?: string; payload?: Record<string, unknown> }[];
  label?: string | number;
  labelFormatter?: (l: string) => string;
  valueSuffix?: string;
}) {
  if (!active || !payload?.length) return null;
  const heading = labelFormatter && typeof label === "string" ? labelFormatter(label) : label;
  return (
    <div
      className="rounded-lg px-3 py-2 text-[11.5px] shadow-lg"
      style={{
        background: "var(--surface-raised)",
        border: "1px solid var(--border-strong)",
        color: "var(--text-primary)",
      }}
    >
      {heading != null && <div className="mb-1 font-semibold">{heading}</div>}
      {payload.map((p, i) => (
        <div key={i} className="flex items-center justify-between gap-3">
          <span className="inline-flex items-center gap-1.5" style={{ color: "var(--text-secondary)" }}>
            <span aria-hidden="true" className="inline-block rounded-sm" style={{ width: 8, height: 8, background: p.color }} />
            {p.name}
          </span>
          <span className="tnum font-medium">
            {typeof p.value === "number" ? p.value.toLocaleString("en-IN") : p.value}
            {valueSuffix}
          </span>
        </div>
      ))}
    </div>
  );
}

/**
 * Horizontal bars for ranked categories with long labels — exit themes, teams.
 *
 * Values are direct-labelled at the bar end, which is both the readability choice for a ranked list
 * and the required relief for the lighter series colours on a white surface.
 */
export function RankedBars({
  data,
  valueSuffix = "",
  color = SERIES[0],
  height,
  maxLabelWidth = 168,
}: {
  data: { label: string; value: number; tone?: string }[];
  valueSuffix?: string;
  color?: string;
  height?: number;
  maxLabelWidth?: number;
}) {
  const computedHeight = height ?? Math.max(120, data.length * 30 + 16);
  return (
    <div style={{ width: "100%", height: computedHeight }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} layout="vertical" margin={{ top: 4, right: 46, bottom: 4, left: 4 }}>
          <CartesianGrid horizontal={false} stroke={GRID} />
          <XAxis type="number" tick={AXIS_TICK} axisLine={false} tickLine={false} />
          <YAxis
            type="category"
            dataKey="label"
            width={maxLabelWidth}
            tick={AXIS_TICK}
            axisLine={false}
            tickLine={false}
          />
          <Tooltip
            content={<ChartTooltip valueSuffix={valueSuffix} />}
            cursor={{ fill: "var(--surface-2)" }}
          />
          <Bar dataKey="value" name="Value" radius={[0, 4, 4, 0]} barSize={13} isAnimationActive={false}>
            {data.map((d, i) => (
              <Cell key={i} fill={d.tone ?? color} />
            ))}
            <LabelList
              dataKey="value"
              position="right"
              formatter={(v: number) => `${v}${valueSuffix}`}
              style={{ fontSize: 10.5, fill: "var(--text-secondary)", fontVariantNumeric: "tabular-nums" }}
            />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

/** Vertical bars for an ordered distribution — rating spread, tenure bands. */
export function ColumnChart({
  data,
  valueSuffix = "",
  color = SERIES[0],
  height = 200,
  labelFormatter,
}: {
  data: { label: string; value: number }[];
  valueSuffix?: string;
  color?: string;
  height?: number;
  labelFormatter?: (l: string) => string;
}) {
  return (
    <div style={{ width: "100%", height }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 16, right: 8, bottom: 4, left: -12 }}>
          <CartesianGrid vertical={false} stroke={GRID} />
          <XAxis
            dataKey="label"
            tick={AXIS_TICK}
            axisLine={AXIS_LINE}
            tickLine={false}
            interval={0}
            tickFormatter={labelFormatter}
          />
          <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} />
          <Tooltip
            content={<ChartTooltip valueSuffix={valueSuffix} labelFormatter={labelFormatter} />}
            cursor={{ fill: "var(--surface-2)" }}
          />
          <Bar dataKey="value" name="Employees" fill={color} radius={[4, 4, 0, 0]} maxBarSize={44} isAnimationActive={false}>
            <LabelList
              dataKey="value"
              position="top"
              style={{ fontSize: 10.5, fill: "var(--text-secondary)", fontVariantNumeric: "tabular-nums" }}
            />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

/**
 * Stacked composition across one categorical axis.
 *
 * Segments are separated by a 2px surface-coloured stroke so adjacent fills never touch, which is
 * what keeps two similar hues readable side by side.
 */
export function StackedBars({
  data,
  series,
  height = 220,
  horizontal = false,
  labelFormatter,
}: {
  data: Record<string, string | number>[];
  series: { key: string; label: string; color: string }[];
  height?: number;
  horizontal?: boolean;
  labelFormatter?: (l: string) => string;
}) {
  return (
    <div style={{ width: "100%", height }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart
          data={data}
          layout={horizontal ? "vertical" : "horizontal"}
          margin={{ top: 8, right: 12, bottom: 4, left: horizontal ? 4 : -12 }}
        >
          <CartesianGrid vertical={horizontal} horizontal={!horizontal} stroke={GRID} />
          {horizontal ? (
            <>
              <XAxis type="number" tick={AXIS_TICK} axisLine={false} tickLine={false} />
              <YAxis type="category" dataKey="label" width={132} tick={AXIS_TICK} axisLine={false} tickLine={false} />
            </>
          ) : (
            <>
              <XAxis
                dataKey="label"
                tick={AXIS_TICK}
                axisLine={AXIS_LINE}
                tickLine={false}
                interval={0}
                tickFormatter={labelFormatter}
              />
              <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} />
            </>
          )}
          <Tooltip content={<ChartTooltip labelFormatter={labelFormatter} />} cursor={{ fill: "var(--surface-2)" }} />
          {series.map((s, i) => (
            <Bar
              key={s.key}
              dataKey={s.key}
              name={s.label}
              stackId="stack"
              fill={s.color}
              stroke="var(--surface-1)"
              strokeWidth={2}
              maxBarSize={46}
              isAnimationActive={false}
              radius={i === series.length - 1 ? (horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0]) : undefined}
            />
          ))}
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

/**
 * A composition bar: the parts of one whole, laid out horizontally with direct labels.
 *
 * Chosen over a stacked chart for single-whole breakdowns — exit type mix, a team's attendance state
 * mix — because with a handful of segments the labels can sit on the bar itself. That removes the
 * legend round trip, and it is the visible-label relief the lighter series colours require on a white
 * surface. Segments are separated by a 2px surface gap so two adjacent hues never touch.
 */
export function CompositionBar({
  segments,
  total,
  valueSuffix = "",
  showLabels = true,
  height = 26,
}: {
  segments: { label: string; value: number; color: string }[];
  total?: number;
  valueSuffix?: string;
  showLabels?: boolean;
  height?: number;
}) {
  const sum = total ?? segments.reduce((a, s) => a + s.value, 0);
  if (sum <= 0) {
    return (
      <p className="text-[11.5px]" style={{ color: "var(--text-secondary)" }}>
        Nothing to show for this selection.
      </p>
    );
  }
  const visible = segments.filter((s) => s.value > 0);

  return (
    <div>
      <div className="flex w-full items-stretch" style={{ height, gap: 2 }} role="img"
        aria-label={visible.map((s) => `${s.label}: ${s.value}${valueSuffix}`).join(", ")}>
        {visible.map((s, i) => {
          const pct = (s.value / sum) * 100;
          const first = i === 0;
          const last = i === visible.length - 1;
          return (
            <div
              key={s.label}
              title={`${s.label}: ${s.value}${valueSuffix} (${pct.toFixed(1)}%)`}
              className="flex items-center justify-center overflow-hidden"
              style={{
                width: `${pct}%`,
                background: s.color,
                borderTopLeftRadius: first ? 4 : 0,
                borderBottomLeftRadius: first ? 4 : 0,
                borderTopRightRadius: last ? 4 : 0,
                borderBottomRightRadius: last ? 4 : 0,
              }}
            >
              {/* Label inside only when the segment is wide enough to hold it legibly. */}
              {showLabels && pct >= 9 && (
                <span className="tnum px-1 text-[10.5px] font-semibold text-white">
                  {s.value}
                  {valueSuffix}
                </span>
              )}
            </div>
          );
        })}
      </div>
      {showLabels && (
        <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
          {visible.map((s) => (
            <li key={s.label} className="inline-flex items-center gap-1.5 text-[11px]">
              <span aria-hidden="true" className="inline-block rounded-sm" style={{ width: 9, height: 9, background: s.color }} />
              <span style={{ color: "var(--text-secondary)" }}>{s.label}</span>
              <span className="tnum font-medium">
                {s.value}
                {valueSuffix}
              </span>
              <span style={{ color: "var(--text-muted)" }}>({((s.value / sum) * 100).toFixed(1)}%)</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Multi-series line trend over months. 2px strokes, 8px markers, shared crosshair tooltip. */
export function TrendLines({
  data,
  series,
  height = 230,
  yDomain,
  valueSuffix = "",
}: {
  data: Record<string, string | number | null>[];
  series: { key: string; label: string; color: string }[];
  height?: number;
  yDomain?: [number | "auto", number | "auto"];
  valueSuffix?: string;
}) {
  return (
    <div style={{ width: "100%", height }}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 10, right: 16, bottom: 4, left: -12 }}>
          <CartesianGrid vertical={false} stroke={GRID} />
          <XAxis
            dataKey="label"
            tick={AXIS_TICK}
            axisLine={AXIS_LINE}
            tickLine={false}
            tickFormatter={(v: string) => (v?.length === 7 ? formatMonth(v) : v)}
          />
          <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} domain={yDomain} />
          <Tooltip
            content={
              <ChartTooltip
                valueSuffix={valueSuffix}
                labelFormatter={(v) => (v?.length === 7 ? formatMonth(v) : v)}
              />
            }
            cursor={{ stroke: "var(--axis)", strokeWidth: 1 }}
          />
          {series.map((s) => (
            <Line
              key={s.key}
              type="monotone"
              dataKey={s.key}
              name={s.label}
              stroke={s.color}
              strokeWidth={2}
              dot={{ r: 4, fill: s.color, stroke: "var(--surface-1)", strokeWidth: 2 }}
              activeDot={{ r: 5.5, stroke: "var(--surface-1)", strokeWidth: 2 }}
              connectNulls
              isAnimationActive={false}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
