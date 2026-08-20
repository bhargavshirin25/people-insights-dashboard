"use client";

import { Badge, InfoTip } from "@/components/ui";
import { METRIC_METHODOLOGY } from "@/lib/methodology";
import type { MetricCard } from "@/lib/types";

/**
 * One above-the-fold metric card.
 *
 * The arrow shows which way the metric moved; the colour shows whether that movement is good, which
 * is not the same thing — attrition rising is an up arrow in red, at-risk falling is a down arrow in
 * green. Direction and judgement are separate channels, and the delta always carries a sign and a
 * word, so neither reading depends on colour alone.
 */
export function MetricTile({ card }: { card: MetricCard }) {
  const how = METRIC_METHODOLOGY[card.key];
  const tip = how ? (
    <InfoTip label={String(card.label).toLowerCase()} method={how.method} formula={how.formula} caveat={how.caveat} />
  ) : null;
  const arrow = card.direction === "up" ? "▲" : card.direction === "down" ? "▼" : "▬";
  const tone =
    card.sentiment === "green" ? "var(--status-good-text)"
    : card.sentiment === "red" ? "var(--status-critical-text)"
    : "var(--status-warning-text)";

  if (!card.available) {
    return (
      <div className="card flex h-full flex-col p-3.5" style={{ borderStyle: "dashed" }}>
        <div className="flex items-start justify-between gap-2">
          <h3
            className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide"
            style={{ color: "var(--text-muted)" }}
          >
            <span className="min-w-0">{card.label}</span>
            {tip}
          </h3>
          <Badge tone="neutral">Not available</Badge>
        </div>
        <div className="mt-1.5 text-[26px] leading-none font-semibold" style={{ color: "var(--text-muted)" }}>
          —
        </div>
        <p className="mt-2 text-[10.5px] leading-relaxed" style={{ color: "var(--text-muted)" }}>
          {card.unavailableReason}
        </p>
      </div>
    );
  }

  return (
    <div className="card flex h-full flex-col p-3.5">
      <h3
        className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide"
        style={{ color: "var(--text-muted)" }}
      >
        <span className="min-w-0">{card.label}</span>
        {tip}
      </h3>

      <div className="mt-1.5 flex items-end gap-2">
        <span className="text-[27px] leading-none font-semibold tracking-tight">{card.displayValue}</span>
        {card.unit && (
          <span className="pb-0.5 text-[10.5px]" style={{ color: "var(--text-muted)" }}>
            {card.unit}
          </span>
        )}
      </div>

      <div className="mt-2 flex items-center gap-1.5 text-[11.5px]" style={{ color: tone }}>
        {card.momAbsolute != null ? (
          <>
            <span aria-hidden="true">{arrow}</span>
            <span className="tnum font-medium">
              {card.momAbsolute >= 0 ? "+" : ""}
              {card.momAbsolute.toFixed(1)}
            </span>
            {card.momPercent != null && (
              <span className="tnum">
                ({card.momPercent >= 0 ? "+" : ""}
                {card.momPercent.toFixed(1)}%)
              </span>
            )}
            <span style={{ color: "var(--text-muted)" }}>MoM</span>
          </>
        ) : (
          <span style={{ color: "var(--text-muted)" }}>No prior-period comparison</span>
        )}
      </div>

      {(card.unavailableReason || card.basis) && (
        <p className="mt-2 text-[10px] leading-relaxed" style={{ color: "var(--text-muted)" }}>
          {card.unavailableReason ?? card.basis}
        </p>
      )}
    </div>
  );
}
