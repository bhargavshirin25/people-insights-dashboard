"use client";

import { MetricTile } from "@/components/MetricTile";
import { NarrativePanel, NarrativeThinking, NarrativeTitle } from "@/components/NarrativePanel";
import { ViewState } from "@/components/ViewState";
import { Button, DataTable, InfoTip, Notice, Panel, Td } from "@/components/ui";
import { api } from "@/lib/api";
import { useFilters } from "@/lib/filters";
import { formatDate } from "@/lib/format";
import { useViewData } from "@/lib/useViewData";
import type { CalendarEvent, Narrative, Overview } from "@/lib/types";
import { useState } from "react";

/** Colour by event type — identity, so a fixed categorical slot each, never cycled. */
const EVENT_STYLE: Record<string, { color: string; glyph: string }> = {
  PROBATION_CONFIRMATION: { color: "var(--series-1)", glyph: "◆" },
  APPRAISAL_MILESTONE: { color: "var(--series-2)", glyph: "★" },
  WORK_ANNIVERSARY: { color: "var(--series-3)", glyph: "●" },
};

export default function OverviewPage() {
  const { data, loading, error } = useViewData<Overview>("/api/dashboard/overview");
  // Loaded separately from the cards, but only once the cards have arrived: a first-time narrative
  // calls a language model, and the brief requires the metric cards above the fold without waiting.
  // Holding it until the overview resolves also keeps two org-wide dataset loads off the wire at once.
  const narrative = useViewData<Narrative>("/api/narrative", undefined, !!data);
  const { query } = useFilters();
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exportingCsv, setExportingCsv] = useState(false);

  async function exportDeck() {
    setExporting(true);
    setExportError(null);
    try {
      await api.download(`/api/export/deck${query()}`, "business-review.pdf");
    } catch (e) {
      setExportError(e instanceof Error ? e.message : "Export failed.");
    } finally {
      setExporting(false);
    }
  }

  async function exportCsv() {
    setExportingCsv(true);
    setExportError(null);
    try {
      await api.download(`/api/export/csv${query()}`, "people-insights.csv");
    } catch (e) {
      setExportError(e instanceof Error ? e.message : "Export failed.");
    } finally {
      setExportingCsv(false);
    }
  }

  return (
    <ViewState loading={loading} error={error}>
      {data && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h1 className="text-[19px] leading-tight font-semibold tracking-tight">
                {data.headline.businessUnit}
              </h1>
              <p className="mt-0.5 text-[11.5px]" style={{ color: "var(--text-secondary)" }}>
                {data.headline.employeesInScope.toLocaleString("en-IN")} employees in scope ·{" "}
                {data.headline.periodLabel} · reporting date {formatDate(data.headline.asOf)}
                {data.dataAsOf && ` · data as of ${formatDate(data.dataAsOf)}`}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <Button onClick={exportCsv} variant="secondary" disabled={exportingCsv}>
                {exportingCsv ? "Preparing CSV…" : "Export CSV"}
              </Button>
              <Button onClick={exportDeck} variant="primary" disabled={exporting}>
                {exporting ? "Building deck…" : "Export business review deck"}
              </Button>
            </div>
          </div>

          {exportError && (
            <Notice title="Export failed" tone="critical">
              {exportError}
            </Notice>
          )}

          {/* Above the fold: every headline metric, no scrolling. */}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
            {data.headline.cards.map((card) => (
              <MetricTile key={card.key} card={card} />
            ))}
          </div>

          {narrative.data ? (
            <NarrativePanel narrative={narrative.data} onChange={narrative.setData} />
          ) : narrative.error ? (
            <Notice title="Narrative summary unavailable" tone="warning">
              {narrative.error.message}
            </Notice>
          ) : (
            <Panel title={<NarrativeTitle busy />}>
              <NarrativeThinking />
            </Panel>
          )}

          <Panel
            title="HR calendar — next 30 days"
            info={
              <InfoTip
                label="the HR calendar"
                method="People events falling in the 30 days after the reporting date, built per employee from dates on their record: probation confirmations due, appraisal milestones, and work anniversaries at 1, 3, 5 and 10 years."
                formula="window = as-of date … as-of date + 30 days
anniversary = date of joining + n years, for n in 1, 3, 5, 10"
                caveat="Anchored to the reporting date, not today. Event types the extract cannot support — contract renewals, which have no end date recorded — are listed as unavailable rather than inferred."
              />
            }
            subtitle={`${formatDate(data.calendar.from)} to ${formatDate(data.calendar.to)} · ${
              data.calendar.totalEvents
            } events`}
            note={data.calendar.unavailableEventTypes.map((u) => u.reason).join(" ")}
          >
            {data.calendar.totalEvents === 0 ? (
              <p className="text-[12px]" style={{ color: "var(--text-secondary)" }}>
                No people events fall in the next 30 days for this selection.
              </p>
            ) : (
              <>
                <div className="mb-3 flex flex-wrap gap-x-3 gap-y-1">
                  {Object.entries(EVENT_STYLE).map(([type, style]) => {
                    // From the server's counts, not the rows: an aggregate-only role receives the
                    // counts with no rows behind them.
                    const count = data.calendar.typeCounts[type] ?? 0;
                    const label = data.calendar.typeLabels[type] ?? type;
                    return (
                      <span
                        key={type}
                        className="inline-flex items-center gap-1.5 text-[11px]"
                        style={{ color: "var(--text-secondary)" }}
                      >
                        <span aria-hidden="true" style={{ color: style.color }}>
                          {style.glyph}
                        </span>
                        {label} ({count})
                      </span>
                    );
                  })}
                </div>
                {data.calendar.eventsVisible ? (
                  <CalendarList events={data.calendar.events} />
                ) : (
                  <p className="text-[11.5px]" style={{ color: "var(--text-muted)" }}>
                    {data.calendar.eventsNote}
                  </p>
                )}
              </>
            )}
          </Panel>
        </div>
      )}
    </ViewState>
  );
}

function CalendarList({ events }: { events: CalendarEvent[] }) {
  const [expanded, setExpanded] = useState(false);
  const shown = expanded ? events : events.slice(0, 12);

  return (
    <>
      <DataTable
        headers={["Date", "In", "Type", "Event", "Employee", "Grade", "Team"]}
        ariaLabel="Upcoming people events"
      >
        {shown.map((e, i) => {
          const style = EVENT_STYLE[e.type] ?? { color: "var(--series-1)", glyph: "•" };
          return (
            <tr key={`${e.employeeId}-${e.date}-${i}`}>
              <Td>{formatDate(e.date)}</Td>
              <Td align="right">{e.daysFromNow}d</Td>
              <Td>
                <span className="inline-flex items-center gap-1.5">
                  <span aria-hidden="true" style={{ color: style.color }}>
                    {style.glyph}
                  </span>
                  <span className="text-[11.5px]">{e.typeLabel}</span>
                </span>
              </Td>
              <Td>{e.title}</Td>
              <Td>{e.employeeName ?? "—"}</Td>
              <Td>{e.grade ?? "—"}</Td>
              <Td>{e.department ?? "—"}</Td>
            </tr>
          );
        })}
      </DataTable>
      {events.length > 12 && (
        <button
          type="button"
          onClick={() => setExpanded((v) => !v)}
          className="mt-2 text-[11px] underline underline-offset-2"
          style={{ color: "var(--text-muted)" }}
        >
          {expanded ? "Show fewer" : `Show all ${events.length} events`}
        </button>
      )}
    </>
  );
}
