"use client";

import { useEffect, useId, useRef, useState, type ReactNode } from "react";

/**
 * How a figure is calculated, shown on demand next to the thing it explains.
 *
 * <p>Every number on this dashboard is derived, and an HRBP defending one in a review needs the
 * definition, not a description. So the body takes the method in prose and the formula verbatim:
 * `formula` is rendered monospaced and is meant to be the same expression the service computes.
 */
export function InfoTip({
  label,
  method,
  formula,
  caveat,
}: {
  /** What is being explained, for screen readers: "How eNPS by survey cycle is calculated". */
  label: string;
  method: ReactNode;
  formula?: string;
  caveat?: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const wrap = useRef<HTMLSpanElement>(null);

  // Dismissal has to cover every way it was opened: Escape and outside clicks for pointer and
  // touch, and focusout for keyboard users tabbing straight through the header.
  useEffect(() => {
    if (!open) {
      return;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setOpen(false);
      }
    };
    const onDown = (e: PointerEvent) => {
      if (wrap.current && !wrap.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("keydown", onKey);
    document.addEventListener("pointerdown", onDown);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onDown);
    };
  }, [open]);

  return (
    <span
      ref={wrap}
      className="relative inline-flex align-middle"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      <button
        type="button"
        aria-label={`How ${label} is calculated`}
        aria-expanded={open}
        aria-describedby={open ? id : undefined}
        className="inline-flex h-[15px] w-[15px] shrink-0 cursor-help items-center justify-center rounded-full text-[10px] leading-none font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-ring)]"
        style={{
          border: "1px solid var(--border-strong)",
          color: open ? "var(--text-primary)" : "var(--text-muted)",
        }}
        onClick={() => setOpen((v) => !v)}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
      >
        i
      </button>
      {open && (
        <span
          id={id}
          role="tooltip"
          className="absolute top-[calc(100%+6px)] left-0 z-50 block w-[min(21rem,78vw)] rounded-lg p-3 text-left text-[11px] leading-relaxed font-normal"
          style={{
            background: "var(--surface-raised)",
            border: "1px solid var(--border-strong)",
            boxShadow: "0 6px 20px rgba(0, 0, 0, 0.16)",
            color: "var(--text-secondary)",
          }}
        >
          <span className="block" style={{ color: "var(--text-primary)" }}>
            {method}
          </span>
          {formula && (
            <code
              className="mt-2 block rounded-md px-2 py-1.5 text-[10.5px] leading-relaxed"
              style={{ background: "var(--surface-2)", color: "var(--text-primary)" }}
            >
              {formula}
            </code>
          )}
          {caveat && (
            <span className="mt-2 block" style={{ color: "var(--text-muted)" }}>
              {caveat}
            </span>
          )}
        </span>
      )}
    </span>
  );
}

/** A titled panel. Charts and tables live inside one of these so spacing stays uniform. */
export function Panel({
  title,
  subtitle,
  actions,
  children,
  note,
  info,
  className = "",
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  note?: ReactNode;
  /** Pass an <InfoTip> to explain how this panel's figures are derived. */
  info?: ReactNode;
  className?: string;
}) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <header
          className="hairline-b flex items-start justify-between gap-4 rounded-t-[10px] px-4 py-3"
          style={{ background: "var(--surface-2)" }}
        >
          <div className="min-w-0">
            {title && (
              <h2 className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight">
                <span className="min-w-0">{title}</span>
                {info}
              </h2>
            )}
            {subtitle && (
              <p className="mt-0.5 text-[11.5px] leading-snug" style={{ color: "var(--text-secondary)" }}>
                {subtitle}
              </p>
            )}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className="p-4">{children}</div>
      {note && (
        <footer
          className="px-4 pb-3 text-[11px] leading-relaxed"
          style={{ color: "var(--text-muted)" }}
        >
          {note}
        </footer>
      )}
    </section>
  );
}

/**
 * A status chip. Status colour never travels alone — every chip carries its label, and the
 * severity variants carry a glyph too, so meaning survives colour-blindness and greyscale print.
 */
export function Badge({
  children,
  tone = "neutral",
  glyph,
}: {
  children: ReactNode;
  tone?: "neutral" | "good" | "warning" | "serious" | "critical" | "info";
  glyph?: string;
}) {
  const tones: Record<string, { bg: string; fg: string; bd: string }> = {
    neutral: { bg: "var(--surface-2)", fg: "var(--text-secondary)", bd: "var(--border-hairline)" },
    // The accent ink rather than --series-1: on its own 12% wash the series hue lands at 3.7:1, which
    // is short of AA for 10.5px type. The stepped ink reads the same and clears it at 5.5:1.
    info: { bg: "color-mix(in srgb, var(--series-1) 12%, transparent)", fg: "var(--accent-ink)", bd: "color-mix(in srgb, var(--series-1) 32%, transparent)" },
    good: { bg: "color-mix(in srgb, var(--status-good) 14%, transparent)", fg: "var(--status-good-text)", bd: "color-mix(in srgb, var(--status-good) 36%, transparent)" },
    warning: { bg: "color-mix(in srgb, var(--status-warning) 20%, transparent)", fg: "var(--status-warning-text)", bd: "color-mix(in srgb, var(--status-warning) 44%, transparent)" },
    serious: { bg: "color-mix(in srgb, var(--status-serious) 18%, transparent)", fg: "var(--status-critical-text)", bd: "color-mix(in srgb, var(--status-serious) 40%, transparent)" },
    critical: { bg: "color-mix(in srgb, var(--status-critical) 14%, transparent)", fg: "var(--status-critical-text)", bd: "color-mix(in srgb, var(--status-critical) 38%, transparent)" },
  };
  const t = tones[tone];
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full border px-2 py-[2px] text-[10.5px] font-medium whitespace-nowrap"
      style={{ background: t.bg, color: t.fg, borderColor: t.bd }}
    >
      {glyph && <span aria-hidden="true">{glyph}</span>}
      {children}
    </span>
  );
}

/** Maps a High/Medium/Low severity onto a status tone plus its glyph. */
export function SeverityBadge({ severity }: { severity: string }) {
  const map: Record<string, { tone: "critical" | "warning" | "neutral"; glyph: string }> = {
    High: { tone: "critical", glyph: "▲" },
    Medium: { tone: "warning", glyph: "◆" },
    Low: { tone: "neutral", glyph: "▪" },
  };
  const m = map[severity] ?? map.Low;
  return (
    <Badge tone={m.tone} glyph={m.glyph}>
      {severity}
    </Badge>
  );
}

export function Button({
  children,
  onClick,
  variant = "secondary",
  disabled,
  type = "button",
  title,
}: {
  children: ReactNode;
  onClick?: () => void;
  variant?: "primary" | "danger" | "secondary" | "ghost";
  disabled?: boolean;
  type?: "button" | "submit";
  title?: string;
}) {
  const base =
    "inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-[12px] font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed";
  const styles: Record<string, string> = {
    primary: "text-white",
    danger: "border",
    secondary: "border",
    ghost: "",
  };
  const inline: Record<string, React.CSSProperties> = {
    primary: { background: "var(--series-1)" },
    /*
     * The action that destroys something. Outlined rather than filled on purpose: a delete sitting in a
     * list of roles or feeds should be unmistakable without being the loudest thing on the screen, which
     * a solid red block beside a solid teal one would be.
     *
     * This borrows the status palette's critical step, which is otherwise reserved for severity. The
     * crossover is deliberate and safe here: the colour sits behind a word that says what the button does,
     * so it is never carrying the meaning alone, and at 7.2:1 on a card it is a legible label rather than
     * an alarm. It is the one red in the application, which is the point — a reader should not have to
     * learn a second one.
     */
    danger: {
      background: "var(--surface-1)",
      borderColor: "color-mix(in srgb, var(--status-critical) 45%, transparent)",
      color: "var(--status-critical-text)",
    },
    secondary: { background: "var(--surface-1)", borderColor: "var(--border-strong)", color: "var(--text-primary)" },
    ghost: { color: "var(--text-secondary)" },
  };
  return (
    <button
      type={type}
      title={title}
      onClick={onClick}
      disabled={disabled}
      className={`${base} ${styles[variant]} ${!disabled ? "hover:opacity-90" : ""}`}
      style={inline[variant]}
    >
      {children}
    </button>
  );
}

/** A full-width message panel: empty states, access denials, load failures. */
export function Notice({
  title,
  children,
  tone = "neutral",
}: {
  title: string;
  children?: ReactNode;
  tone?: "neutral" | "warning" | "critical" | "info";
}) {
  const border: Record<string, string> = {
    neutral: "var(--border-strong)",
    info: "var(--series-1)",
    warning: "var(--status-warning)",
    critical: "var(--status-critical)",
  };
  return (
    <div
      className="card p-4"
      style={{ borderLeftWidth: 3, borderLeftColor: border[tone] }}
      role={tone === "critical" ? "alert" : undefined}
    >
      <h3 className="text-[12.5px] font-semibold">{title}</h3>
      {children && (
        <div className="mt-1 text-[11.5px] leading-relaxed" style={{ color: "var(--text-secondary)" }}>
          {children}
        </div>
      )}
    </div>
  );
}

export function Skeleton({ rows = 3, height = 14 }: { rows?: number; height?: number }) {
  return (
    <div className="animate-pulse space-y-2" aria-hidden="true">
      {Array.from({ length: rows }).map((_, i) => (
        <div
          key={i}
          style={{ height, background: "var(--surface-2)", width: `${100 - i * 8}%` }}
          className="rounded"
        />
      ))}
    </div>
  );
}

/** A horizontally scrollable table. Wide content scrolls inside itself, never the page. */
export function DataTable({
  headers,
  children,
  ariaLabel,
}: {
  headers: (string | { label: string; align?: "left" | "right" })[];
  children: ReactNode;
  ariaLabel?: string;
}) {
  return (
    <div className="scroll-x -mx-1">
      <table className="w-full min-w-full text-[12px]" aria-label={ariaLabel}>
        <thead>
          <tr className="hairline-b">
            {headers.map((h, i) => {
              const label = typeof h === "string" ? h : h.label;
              const align = typeof h === "string" ? "left" : (h.align ?? "left");
              return (
                <th
                  key={i}
                  scope="col"
                  className={`px-2 py-2 text-[10.5px] font-semibold uppercase tracking-wide ${
                    align === "right" ? "text-right" : "text-left"
                  }`}
                  style={{ color: "var(--text-muted)" }}
                >
                  {label}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

export function Td({
  children,
  align = "left",
  className = "",
  colSpan,
}: {
  children: ReactNode;
  align?: "left" | "right";
  className?: string;
  colSpan?: number;
}) {
  return (
    <td
      colSpan={colSpan}
      className={`px-2 py-2 align-top ${align === "right" ? "text-right tnum" : ""} ${className}`}
      style={{ borderTop: "1px solid var(--border-hairline)" }}
    >
      {children}
    </td>
  );
}
