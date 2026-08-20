"use client";

import { Badge, Button, Panel, SeverityBadge } from "@/components/ui";
import { api } from "@/lib/api";
import { useFilters } from "@/lib/filters";
import { formatDateTime } from "@/lib/format";
import type { Narrative } from "@/lib/types";
import { useEffect, useState } from "react";

/**
 * The narrative panel's heading: a twinkling sparkle mark beside a named label.
 *
 * The label is real text rather than a screen-reader-only string behind a decorative glyph, so the
 * panel carries its own accessible name and the mark can be purely visual.
 *
 * The sparkles keep a slow ambient twinkle at rest and quicken while a summary is being written, so
 * the panel reads as the generated one at a glance without the motion claiming that work is in
 * progress — the stage list and the spinner in the body are what signal that.
 */
export function NarrativeTitle({ busy = false }: { busy?: boolean }) {
  return (
    <span className="ai-title inline-flex items-center gap-1.5" data-busy={busy ? "true" : undefined}>
      <svg
        aria-hidden="true"
        className="ai-sparkle"
        viewBox="0 0 16 16"
        width="15"
        height="15"
        fill="currentColor"
        style={{ color: "var(--series-1)" }}
      >
        <path
          className="ai-sparkle-lg"
          d="M6.5 1 7.68 5.12 11.8 6.3 7.68 7.48 6.5 11.6 5.32 7.48 1.2 6.3 5.32 5.12Z"
        />
        <path
          className="ai-sparkle-sm"
          d="M12.1 9.2 12.72 11.38 14.9 12 12.72 12.62 12.1 14.8 11.48 12.62 9.3 12 11.48 11.38Z"
        />
      </svg>
      <span className="ai-title-text">AI summary</span>
    </span>
  );
}

/** The stages a generation actually goes through, in the order the backend does them. */
const STAGES = [
  "Reading the figures on this view…",
  "Looking for anomalies worth flagging…",
  "Writing the summary…",
  "Verifying every figure it cites…",
];

const SPINNER = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧"];

/**
 * The panel's body while a summary is being written.
 *
 * <p>The stage labels are not theatre: they name the steps the backend performs in the order it
 * performs them, ending on figure verification, which is the step that can reject a finished summary.
 * The sequence advances and then holds on the last stage rather than looping, because looping would
 * suggest work repeating when it is simply still going.
 */
export function NarrativeThinking() {
  const [stage, setStage] = useState(0);
  const [frame, setFrame] = useState(0);

  useEffect(() => {
    const advance = setInterval(
      () => setStage((s) => (s < STAGES.length - 1 ? s + 1 : s)),
      1800,
    );
    return () => clearInterval(advance);
  }, []);

  useEffect(() => {
    const spin = setInterval(() => setFrame((f) => (f + 1) % SPINNER.length), 110);
    return () => clearInterval(spin);
  }, []);

  return (
    <div aria-busy="true">
      <div className="mb-3 flex items-center gap-2">
        <span
          aria-hidden="true"
          className="ai-spinner text-[12px] leading-none"
          style={{ color: "var(--series-1)" }}
        >
          {SPINNER[frame]}
        </span>
        {/* Polite, so a screen reader hears the stage change without losing the user's place. */}
        <span
          aria-live="polite"
          className="text-[11.5px]"
          style={{ color: "var(--text-secondary)" }}
        >
          {STAGES[stage]}
        </span>
      </div>

      <div
        className="space-y-2"
        style={{ borderLeft: "3px solid var(--series-1)", paddingLeft: 14 }}
        aria-hidden="true"
      >
        {[100, 96, 88, 62].map((width, i) => (
          <div
            key={i}
            className="ai-shimmer rounded"
            style={{ height: 11, width: `${width}%`, animationDelay: `${i * 140}ms` }}
          />
        ))}
      </div>
    </div>
  );
}

/**
 * The AI narrative summary, with regenerate and inline edit.
 *
 * The cited-figures list is not decoration: the backend refuses to publish a narrative containing a
 * number that is not in this list, so exposing it is what makes the traceability claim checkable by
 * the person reading it.
 */
export function NarrativePanel({
  narrative,
  onChange,
}: {
  narrative: Narrative;
  onChange: (next: Narrative) => void;
}) {
  const { query } = useFilters();
  const [busy, setBusy] = useState(false);
  const [regenerating, setRegenerating] = useState(false);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(narrative.text);
  const [error, setError] = useState<string | null>(null);
  const [showFigures, setShowFigures] = useState(false);

  useEffect(() => {
    setDraft(narrative.text);
    setEditing(false);
  }, [narrative.id, narrative.text]);

  async function regenerate() {
    setBusy(true);
    setRegenerating(true);
    setError(null);
    try {
      onChange(await api.post<Narrative>(`/api/narrative/regenerate${query()}`));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not regenerate the summary.");
    } finally {
      setBusy(false);
      setRegenerating(false);
    }
  }

  async function save() {
    setBusy(true);
    setError(null);
    try {
      onChange(await api.post<Narrative>("/api/narrative/edit", { id: narrative.id, text: draft }));
      setEditing(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not save the edit.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel
      title={<NarrativeTitle busy={regenerating} />}
      subtitle={
        <span className="flex flex-wrap items-center gap-2">
          <span>Generated {formatDateTime(narrative.generatedAt)}</span>
          {narrative.editedBy ? (
            <Badge tone="info" glyph="✎">
              Modified by {narrative.editedByName ?? narrative.editedBy}
            </Badge>
          ) : narrative.fallback ? (
            <Badge tone="warning" glyph="◆">
              Template summary — model unavailable
            </Badge>
          ) : (
            <Badge tone="good" glyph="✓">
              {narrative.model} · figures verified
            </Badge>
          )}
        </span>
      }
      actions={
        <>
          {editing ? (
            <>
              <Button onClick={save} variant="primary" disabled={busy || !draft.trim()}>
                Save
              </Button>
              <Button
                onClick={() => {
                  setDraft(narrative.text);
                  setEditing(false);
                }}
                variant="ghost"
                disabled={busy}
              >
                Cancel
              </Button>
            </>
          ) : (
            <>
              <Button onClick={() => setEditing(true)} disabled={busy}>
                Edit
              </Button>
              <Button onClick={regenerate} disabled={busy}>
                {regenerating ? "Writing…" : busy ? "Working…" : "Regenerate"}
              </Button>
            </>
          )}
        </>
      }
    >
      {regenerating ? (
        <NarrativeThinking />
      ) : editing ? (
        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          rows={5}
          className="w-full rounded-md p-3 text-[13px] leading-relaxed"
          style={{
            background: "var(--surface-2)",
            border: "1px solid var(--border-strong)",
            color: "var(--text-primary)",
          }}
          aria-label="Narrative summary text"
        />
      ) : (
        /* Keyed on the narrative so a regenerated summary animates in rather than swapping silently. */
        <p
          key={narrative.id}
          className="ai-rise text-[13.5px] leading-[1.65]"
          style={{ borderLeft: "3px solid var(--series-1)", paddingLeft: 14 }}
        >
          {narrative.text}
        </p>
      )}

      {error && (
        <p className="mt-2 text-[11.5px]" style={{ color: "var(--status-critical-text)" }} role="alert">
          {error}
        </p>
      )}

      {!regenerating && narrative.anomalies.length > 0 && (
        <div className="mt-4">
          <h4 className="mb-2 text-[10.5px] font-semibold uppercase tracking-wide" style={{ color: "var(--text-muted)" }}>
            Anomalies requiring attention
          </h4>
          <ul className="space-y-1.5">
            {narrative.anomalies.map((a, i) => (
              <li key={i} className="flex items-start gap-2 text-[11.5px] leading-snug">
                <SeverityBadge severity={a.severity} />
                <span>
                  <span className="font-medium">{a.metric}</span>
                  <span style={{ color: "var(--text-secondary)" }}> — {a.description}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {!regenerating && narrative.citedFigures.length > 0 && (
        <div className="mt-4">
          <button
            type="button"
            onClick={() => setShowFigures((v) => !v)}
            className="text-[10.5px] underline underline-offset-2"
            style={{ color: "var(--text-muted)" }}
          >
            {showFigures ? "Hide" : "Show"} the {narrative.citedFigures.length} figures this summary may cite
          </button>
          {showFigures && (
            <dl className="mt-2 grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-2">
              {narrative.citedFigures.map((f, i) => (
                <div key={i} className="flex justify-between gap-3 text-[11px]">
                  <dt style={{ color: "var(--text-secondary)" }}>{f.label}</dt>
                  <dd className="tnum font-medium">{f.value}</dd>
                </div>
              ))}
            </dl>
          )}
        </div>
      )}
    </Panel>
  );
}
