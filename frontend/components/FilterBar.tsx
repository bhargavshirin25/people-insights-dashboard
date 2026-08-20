"use client";

import { Badge, Button } from "@/components/ui";
import { useFilters } from "@/lib/filters";
import { PERIOD_LABELS } from "@/lib/format";
import { useSession } from "@/lib/session";
import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";

/*
 * The shells every control in the row is built from.
 *
 * They are shared constants rather than repeated class lists because the row only reads as a row if
 * the height, radius, border and type size never drift apart between controls — and the whole reason
 * the row looked wrong in Safari is that the platform was picking those values per widget type.
 *
 * Horizontal padding is deliberately not in the base: `px-2` and a later `pr-6` are the same
 * specificity, so which one won would depend on the order Tailwind happened to emit them in. Each
 * control states its own padding instead.
 */
const CONTROL =
  "filter-control h-7 rounded-md border border-[var(--border-strong)] bg-[var(--surface-2)] text-[11.5px] leading-none text-[var(--text-primary)] transition-colors hover:border-[var(--axis)] focus-visible:border-[var(--series-1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-ring)]";

const SELECT = `${CONTROL} cursor-pointer pl-2 pr-6`;
const DATE_INPUT = `${CONTROL} px-2`;
const TRIGGER = `${CONTROL} inline-flex items-center gap-1.5 px-2 disabled:cursor-not-allowed disabled:opacity-50`;

/** A non-interactive shell for a control group, matching the height of the real controls. */
const CHIP =
  "inline-flex h-7 items-center gap-2 rounded-md border border-[var(--border-strong)] bg-[var(--surface-2)] px-2 transition-colors focus-within:border-[var(--series-1)]";

const LABEL = "whitespace-nowrap text-[11px] font-medium text-[var(--text-secondary)]";

/**
 * The multi-level filter row: BU, grade band, location, tenure range and period.
 *
 * One row above the views, and one shared state, so every card and chart moves together on a change.
 * The BU control only ever lists the units the session is scoped to; naming another one is refused by
 * the API regardless, so this is a convenience rather than the control.
 */
export function FilterBar() {
  const { filters, options, setFilters, reset, activeCount } = useFilters();
  const { session } = useSession();
  const businessUnits = options?.businessUnits ?? session?.assignedBus ?? [];

  return (
    <div className="card no-print flex flex-wrap items-center gap-x-3 gap-y-2 px-3 py-2.5">
      {businessUnits.length > 1 ? (
        <SelectField
          label="Business unit"
          value={filters.bu ?? "ALL"}
          onChange={(value) => setFilters({ bu: value === "ALL" ? null : value })}
        >
          <option value="ALL">All in my scope</option>
          {businessUnits.map((bu) => (
            <option key={bu} value={bu}>
              {bu}
            </option>
          ))}
        </SelectField>
      ) : (
        <Badge tone="info">{businessUnits[0] ?? "—"}</Badge>
      )}

      <MultiSelect
        label="Grade"
        options={options?.grades ?? []}
        selected={filters.grades}
        onChange={(grades) => setFilters({ grades })}
      />

      <MultiSelect
        label="Location"
        options={options?.locations ?? []}
        selected={filters.locations}
        onChange={(locations) => setFilters({ locations })}
      />

      <TenureRange
        max={Math.max(1, Math.round(options?.tenureMaxYears ?? 20))}
        min={filters.tenureMin}
        maxValue={filters.tenureMax}
        onChange={(tenureMin, tenureMax) => setFilters({ tenureMin, tenureMax })}
      />

      <SelectField
        label="Period"
        value={filters.period}
        onChange={(period) => setFilters({ period })}
      >
        {Object.entries(PERIOD_LABELS).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </SelectField>

      {filters.period === "CUSTOM" && (
        <span className="inline-flex items-center gap-1.5">
          <input
            type="date"
            value={filters.from ?? ""}
            onChange={(e) => setFilters({ from: e.target.value || null })}
            className={DATE_INPUT}
            aria-label="Custom range start"
          />
          <span className={LABEL}>to</span>
          <input
            type="date"
            value={filters.to ?? ""}
            onChange={(e) => setFilters({ to: e.target.value || null })}
            className={DATE_INPUT}
            aria-label="Custom range end"
          />
        </span>
      )}

      {activeCount > 0 && (
        <span className="ml-auto flex items-center gap-2">
          <Badge tone="info">
            {activeCount} filter{activeCount === 1 ? "" : "s"} active
          </Badge>
          <Button variant="ghost" onClick={reset}>
            Clear
          </Button>
        </span>
      )}
    </div>
  );
}

/**
 * The chevron shared by both dropdowns.
 *
 * Drawn rather than typed. The ▾ character is supplied by the platform font, so its size, weight and
 * baseline differed between Safari and Chrome — the same class of problem as the native widgets, and
 * at this size it rendered closer to a dot than an arrow.
 */
function Chevron({ className = "" }: { className?: string }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 10 6"
      width="9"
      height="6"
      className={`shrink-0 ${className}`}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M1 1.2 5 4.8 9 1.2" />
    </svg>
  );
}

/**
 * A labelled select.
 *
 * The chevron is an absolutely positioned span rather than a background-image data URI, so it takes
 * its colour from the theme tokens like everything else — a baked-in SVG fill would need a second
 * copy for the dark surface.
 */
function SelectField({
  label,
  value,
  onChange,
  children,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  children: ReactNode;
}) {
  return (
    <label className="inline-flex items-center gap-1.5">
      <span className={LABEL}>{label}</span>
      <span className="relative inline-flex items-center">
        <select value={value} onChange={(e) => onChange(e.target.value)} className={SELECT}>
          {children}
        </select>
        <Chevron className="pointer-events-none absolute top-1/2 right-1.5 -translate-y-1/2 text-[var(--text-muted)]" />
      </span>
    </label>
  );
}

/** A checkbox dropdown. Multi-select without pulling in a component library. */
function MultiSelect({
  label,
  options,
  selected,
  onChange,
}: {
  label: string;
  options: string[];
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, [open]);

  const summary =
    selected.length === 0 ? "All" : selected.length <= 2 ? selected.join(", ") : `${selected.length} selected`;

  return (
    <div ref={ref} className="relative inline-flex">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        disabled={options.length === 0}
        className={TRIGGER}
        aria-expanded={open}
      >
        <span className={LABEL}>{label}</span>
        {/* Muted while nothing is picked, so an untouched filter is legible as untouched. */}
        <span className={selected.length === 0 ? "text-[var(--text-muted)]" : "font-medium"}>
          {summary}
        </span>
        <Chevron
          className={`text-[var(--text-muted)] transition-transform ${open ? "rotate-180" : ""}`}
        />
      </button>

      {open && (
        <div
          className="absolute top-full left-0 z-30 mt-1 max-h-64 w-52 overflow-y-auto rounded-md border border-[var(--border-strong)] bg-[var(--surface-raised)] p-1 shadow-xl"
        >
          {selected.length > 0 && (
            <button
              type="button"
              onClick={() => onChange([])}
              className="mb-1 w-full rounded px-2 py-1 text-left text-[11px] text-[var(--text-muted)] underline underline-offset-2 hover:text-[var(--text-primary)]"
            >
              Clear {label.toLowerCase()}
            </button>
          )}
          {options.map((option) => {
            const checked = selected.includes(option);
            return (
              <label
                key={option}
                // A checked row wore the same fill as a hovered one, so the selection was only legible
                // from the checkbox. The accent separates the two states.
                className={`flex cursor-pointer items-center gap-2 rounded px-2 py-1 text-[11.5px] transition-colors hover:bg-[var(--surface-2)] ${
                  checked ? "bg-[var(--accent-wash)] font-medium text-[var(--accent-ink)]" : ""
                }`}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() =>
                    onChange(checked ? selected.filter((s) => s !== option) : [...selected, option])
                  }
                  className="h-3.5 w-3.5 shrink-0 cursor-pointer accent-[var(--series-1)]"
                />
                {option}
              </label>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** Tenure slider pair. Two range inputs, kept ordered. */
function TenureRange({
  max,
  min,
  maxValue,
  onChange,
}: {
  max: number;
  min: number | null;
  maxValue: number | null;
  onChange: (min: number | null, max: number | null) => void;
}) {
  const lo = min ?? 0;
  const hi = maxValue ?? max;
  const active = min != null || maxValue != null;

  return (
    <div className={CHIP}>
      <span className={LABEL}>Tenure</span>
      <input
        type="range"
        min={0}
        max={max}
        step={0.5}
        value={lo}
        onChange={(e) => {
          const next = Number(e.target.value);
          onChange(
            next === 0 ? null : next,
            Math.max(next, hi) === max && maxValue == null ? null : Math.max(next, hi),
          );
        }}
        className="filter-range w-16"
        aria-label="Minimum tenure in years"
      />
      <input
        type="range"
        min={0}
        max={max}
        step={0.5}
        value={hi}
        onChange={(e) => {
          const next = Number(e.target.value);
          onChange(min, next === max ? null : Math.max(next, lo));
        }}
        className="filter-range w-16"
        aria-label="Maximum tenure in years"
      />
      <span
        className={`tnum text-[11px] whitespace-nowrap ${
          active ? "font-medium text-[var(--text-primary)]" : "text-[var(--text-muted)]"
        }`}
      >
        {lo}–{hi === max ? `${max}+` : hi}y
      </span>
    </div>
  );
}
