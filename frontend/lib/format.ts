/** Presentation helpers. All figures shown in the UI come from the API — nothing is derived here. */

export function formatNumber(value: number | null | undefined, digits = 0): string {
  if (value == null || Number.isNaN(value)) return "—";
  return value.toLocaleString("en-IN", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function formatPercent(value: number | null | undefined, digits = 1): string {
  if (value == null || Number.isNaN(value)) return "—";
  return `${value.toFixed(digits)}%`;
}

export function formatSignedPercent(value: number | null | undefined, digits = 1): string {
  if (value == null || Number.isNaN(value)) return "—";
  return `${value >= 0 ? "+" : ""}${value.toFixed(digits)}%`;
}

export function formatSigned(value: number | null | undefined, digits = 0): string {
  if (value == null || Number.isNaN(value)) return "—";
  return `${value >= 0 ? "+" : ""}${value.toFixed(digits)}`;
}

/** Indian-format currency, abbreviated to lakh and crore as HR reporting does. */
export function formatInr(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return "—";
  if (value >= 10_000_000) return `₹${(value / 10_000_000).toFixed(2)} Cr`;
  if (value >= 100_000) return `₹${(value / 100_000).toFixed(2)} L`;
  return `₹${Math.round(value).toLocaleString("en-IN")}`;
}

/**
 * The period control's options, in one place because the filter row and the assistant panel both
 * name the period in view and a reader comparing them would notice two different words for it.
 */
export const PERIOD_LABELS: Record<string, string> = {
  LAST_30_DAYS: "Last month",
  LAST_QUARTER: "Last quarter",
  YTD: "FY to date",
  CUSTOM: "Custom range",
};

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "2026-07" → "Jul 2026". */
export function formatMonth(yearMonth: string | null | undefined): string {
  if (!yearMonth) return "—";
  const [y, m] = yearMonth.split("-");
  const index = Number(m) - 1;
  return index >= 0 && index < 12 ? `${MONTHS[index]} ${y}` : yearMonth;
}

/** "2026-07-31" → "31 Jul 2026". */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const [y, m, d] = iso.split("T")[0].split("-");
  const index = Number(m) - 1;
  return index >= 0 && index < 12 ? `${Number(d)} ${MONTHS[index]} ${y}` : iso;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** Series slot colours, referenced by role so light and dark swap in one place. */
export const SERIES = [
  "var(--series-1)",
  "var(--series-2)",
  "var(--series-3)",
  "var(--series-4)",
  "var(--series-5)",
  "var(--series-6)",
  "var(--series-7)",
  "var(--series-8)",
] as const;

/**
 * Assigns a colour by an entity's identity rather than its position in a filtered list, so
 * removing a series never repaints the survivors.
 */
export function seriesColorFor(key: string, ordered: readonly string[]): string {
  const index = ordered.indexOf(key);
  return SERIES[(index < 0 ? 0 : index) % SERIES.length];
}

export const STATUS_COLORS = {
  good: "var(--status-good)",
  warning: "var(--status-warning)",
  serious: "var(--status-serious)",
  critical: "var(--status-critical)",
} as const;

/** Sequential blue ramp for the heat map — one hue, light to dark. */
const SEQ = [
  "var(--seq-100)",
  "var(--seq-200)",
  "var(--seq-300)",
  "var(--seq-400)",
  "var(--seq-500)",
  "var(--seq-600)",
  "var(--seq-700)",
];

/** Maps a value onto the sequential ramp. Returns the step plus whether ink must go light. */
export function sequentialStep(value: number, min: number, max: number): { bg: string; dark: boolean } {
  if (max <= min) return { bg: SEQ[0], dark: false };
  const t = Math.max(0, Math.min(1, (value - min) / (max - min)));
  const index = Math.min(SEQ.length - 1, Math.round(t * (SEQ.length - 1)));
  // Steps 400 and above are dark enough that text on them must be light.
  return { bg: SEQ[index], dark: index >= 3 };
}
