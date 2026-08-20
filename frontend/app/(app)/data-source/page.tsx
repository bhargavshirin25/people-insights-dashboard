"use client";

import { Badge, Button, DataTable, Notice, Panel, Skeleton, Td } from "@/components/ui";
import { api, ApiError } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import type {
  ApiSourceView,
  DataStatus,
  FeedRow,
  ImportReport,
  OpenPosition,
  SourceTest,
  TableInfo,
} from "@/lib/types";
import { useCallback, useEffect, useRef, useState } from "react";

interface SourceDraft {
  id: string | null;
  name: string;
  url: string;
  headersJson: string;
  jsonPath: string;
  keyField: string;
  intervalSeconds: number;
  enabled: boolean;
  replaceEachRun: boolean;
}

const EMPTY_SOURCE: SourceDraft = {
  id: null,
  name: "",
  url: "",
  headersJson: "",
  jsonPath: "",
  keyField: "",
  intervalSeconds: 300,
  enabled: true,
  replaceEachRun: true,
};

const INPUT =
  "mt-1 w-full rounded-md px-2.5 py-1.5 text-[12.5px] bg-[var(--surface-2)] border border-[var(--border-strong)] text-[var(--text-primary)]";

/**
 * Where the dashboard's data comes from: the workbook ingest, file imports, and polled API endpoints.
 *
 * This replaced the admin console. Its BU-to-HRBP mapping and user roles moved to the access page, where a
 * role decides both what a person opens and which business units they read — so what is left here is the
 * data itself, which is what the screen is now named after.
 */
export default function DataSourcePage() {
  const [status, setStatus] = useState<DataStatus | null>(null);
  const [positions, setPositions] = useState<OpenPosition[]>([]);
  const [tables, setTables] = useState<TableInfo[] | null>(null);
  const [sources, setSources] = useState<ApiSourceView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Import
  const [importTable, setImportTable] = useState("");
  const [importMode, setImportMode] = useState<"APPEND" | "REPLACE">("APPEND");
  const [report, setReport] = useState<ImportReport | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  // API sources
  const [draft, setDraft] = useState<SourceDraft>(EMPTY_SOURCE);
  const [test, setTest] = useState<SourceTest | null>(null);
  const [rows, setRows] = useState<Record<string, FeedRow[]>>({});

  const load = useCallback(async () => {
    try {
      const [dataStatus, openPositions, tableList, sourceList] = await Promise.all([
        api.get<DataStatus>("/api/admin/data-status"),
        api.get<OpenPosition[]>("/api/admin/open-positions"),
        api.get<TableInfo[]>("/api/data-source/tables"),
        api.get<ApiSourceView[]>("/api/data-source/api-sources"),
      ]);
      setStatus(dataStatus);
      setPositions(openPositions);
      setTables(tableList);
      setSources(sourceList);
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not load the data-source configuration.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function refreshData() {
    setBusy(true);
    setError(null);
    try {
      await api.post("/api/admin/refresh-data");
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The refresh could not be started.");
    } finally {
      setBusy(false);
    }
  }

  async function runImport() {
    const file = fileInput.current?.files?.[0];
    if (!file || !importTable) {
      return;
    }
    setBusy(true);
    setError(null);
    setReport(null);
    try {
      const body = new FormData();
      body.append("file", file);
      body.append("table", importTable);
      body.append("mode", importMode);
      // FormData rather than JSON, so the browser sets the multipart boundary; the api helper still
      // attaches the CSRF header.
      const result = await api.upload<ImportReport>("/api/data-source/import", body);
      setReport(result);
      if (fileInput.current) {
        fileInput.current.value = "";
      }
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The import failed.");
    } finally {
      setBusy(false);
    }
  }

  async function testSource() {
    setBusy(true);
    setTest(null);
    setError(null);
    try {
      setTest(
        await api.post<SourceTest>("/api/data-source/api-sources/test", {
          url: draft.url,
          headersJson: draft.headersJson,
          jsonPath: draft.jsonPath,
        }),
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The test could not be run.");
    } finally {
      setBusy(false);
    }
  }

  async function saveSource() {
    setBusy(true);
    setError(null);
    try {
      await api.post("/api/data-source/api-sources", draft);
      setDraft(EMPTY_SOURCE);
      setTest(null);
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The source could not be saved.");
    } finally {
      setBusy(false);
    }
  }

  async function runSource(source: ApiSourceView) {
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/data-source/api-sources/${source.source.id}/run`);
      await load();
      await showRows(source);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The run failed.");
    } finally {
      setBusy(false);
    }
  }

  async function deleteSource(source: ApiSourceView) {
    setBusy(true);
    setError(null);
    try {
      await api.del(`/api/data-source/api-sources/${source.source.id}`);
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The source could not be deleted.");
    } finally {
      setBusy(false);
    }
  }

  async function showRows(source: ApiSourceView) {
    try {
      const landed = await api.get<FeedRow[]>(
        `/api/data-source/api-sources/${source.source.id}/rows?limit=5`,
      );
      setRows((r) => ({ ...r, [source.source.id]: landed }));
    } catch {
      // A missing preview is not worth an error banner; the row count above it still tells the story.
    }
  }

  const selected = tables?.find((t) => t.name === importTable);

  return (
    <div className="space-y-4">
      <Panel
        title="Data source"
        subtitle="Where the figures come from: the HR Ops workbooks, files loaded by hand, and API endpoints polled on a schedule."
      >
        {!status ? (
          <Skeleton rows={3} />
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Fact label="Last ingest" value={status.lastRun ? formatDateTime(status.lastRun) : "never"} />
            <Fact label="Data as of" value={status.dataAsOf} />
            <Fact
              label="Rows held"
              value={Object.values(status.counts ?? {})
                .reduce((a, b) => a + b, 0)
                .toLocaleString("en-IN")}
            />
            <Fact label="Workbook directory" value={status.sourceDirectory} mono />
          </div>
        )}
        {status?.warnings && status.warnings.length > 0 && (
          <ul className="mt-3 space-y-1">
            {status.warnings.map((w) => (
              <li key={w} className="text-[11px]" style={{ color: "var(--status-warning-text)" }}>
                {w}
              </li>
            ))}
          </ul>
        )}
        <div className="mt-3">
          <Button variant="secondary" onClick={() => void refreshData()} disabled={busy}>
            Re-run the workbook ingest
          </Button>
          <span className="ml-2 text-[11px]" style={{ color: "var(--text-muted)" }}>
            Reads the seven workbooks from the directory above and replaces the people tables.
          </span>
        </div>
      </Panel>

      {error && (
        <Notice title="That did not work" tone="critical">
          {error}
        </Notice>
      )}

      <Panel
        title="Import a file into a table"
        subtitle="CSV or .xlsx. Headings are matched to column names, so a file may carry any subset of a table's columns."
        note="For the seven HR Ops datasets the workbook ingest above is the supported path — it knows their child tables and derived columns. Use this to top up a table, correct rows, or load a table the ingest knows nothing about."
      >
        {!tables ? (
          <Skeleton rows={4} />
        ) : (
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-3">
              <label className="block">
                <span className="text-[11px] font-medium" style={{ color: "var(--text-secondary)" }}>
                  Target table
                </span>
                <select
                  value={importTable}
                  onChange={(e) => setImportTable(e.target.value)}
                  className={INPUT}
                >
                  <option value="">Choose a table…</option>
                  {tables.map((t) => (
                    <option key={t.name} value={t.name}>
                      {t.name} ({t.rowCount.toLocaleString("en-IN")} rows)
                    </option>
                  ))}
                </select>
              </label>

              <label className="block">
                <span className="text-[11px] font-medium" style={{ color: "var(--text-secondary)" }}>
                  File
                </span>
                <input ref={fileInput} type="file" accept=".csv,.xlsx,.xlsm,.txt" className={INPUT} />
              </label>

              <label className="block">
                <span className="text-[11px] font-medium" style={{ color: "var(--text-secondary)" }}>
                  Mode
                </span>
                {/* Replace empties the table first, so the control itself says so before the warning does. */}
                <select
                  value={importMode}
                  onChange={(e) => setImportMode(e.target.value as "APPEND" | "REPLACE")}
                  className={INPUT}
                  style={
                    importMode === "REPLACE"
                      ? {
                          borderColor: "color-mix(in srgb, var(--status-critical) 55%, transparent)",
                          color: "var(--status-critical-text)",
                          background: "color-mix(in srgb, var(--status-critical) 8%, var(--surface-2))",
                        }
                      : undefined
                  }
                >
                  <option value="APPEND">Append to what is there</option>
                  <option value="REPLACE">Replace — empty the table first</option>
                </select>
              </label>
            </div>

            {selected && (
              <div
                className="rounded-md p-2.5 text-[11px] leading-relaxed"
                style={{ background: "var(--surface-2)", color: "var(--text-secondary)" }}
              >
                <strong>{selected.name}</strong> columns —{" "}
                {selected.columns
                  .map((c) => c.name + (c.nullable || c.autoOrDefaulted ? "" : " *"))
                  .join(", ")}
                <span className="mt-1 block" style={{ color: "var(--text-muted)" }}>
                  Columns marked * must appear as headings in the file. Anything else the file omits keeps
                  the database&rsquo;s own default.
                </span>
                {importMode === "REPLACE" && selected.rowCount > 0 && (
                  <span
                    className="mt-1 block font-medium"
                    style={{ color: "var(--status-critical-text)" }}
                  >
                    Replace will delete the {selected.rowCount.toLocaleString("en-IN")} row
                    {selected.rowCount === 1 ? "" : "s"} currently in this table.
                  </span>
                )}
              </div>
            )}

            <Button
              variant="primary"
              onClick={() => void runImport()}
              disabled={busy || !importTable}
            >
              {busy ? "Importing…" : "Import"}
            </Button>

            {report && (
              <div
                className="rounded-md p-3 text-[11.5px] leading-relaxed"
                style={{
                  background: "var(--surface-2)",
                  borderLeft: `3px solid ${report.rowsRejected > 0 ? "var(--status-warning)" : "var(--accent-ink)"}`,
                }}
              >
                <p
                  className="font-medium"
                  style={{ color: report.rowsRejected > 0 ? undefined : "var(--accent-ink)" }}
                >
                  {report.rowsWritten.toLocaleString("en-IN")} row
                  {report.rowsWritten === 1 ? "" : "s"} written into {report.table}
                  {report.deletedFirst > 0 &&
                    `, replacing ${report.deletedFirst.toLocaleString("en-IN")}`}
                  {report.rowsRejected > 0 && ` · ${report.rowsRejected} rejected`}
                </p>
                <p className="mt-1" style={{ color: "var(--text-secondary)" }}>
                  Mapped: {report.mappedColumns.join(", ")}
                </p>
                {report.skippedHeadings.length > 0 && (
                  <p style={{ color: "var(--status-warning-text)" }}>
                    Headings with no matching column, ignored: {report.skippedHeadings.join(", ")}
                  </p>
                )}
                {report.fileTruncated && (
                  <p style={{ color: "var(--status-warning-text)" }}>
                    The file was longer than the import cap and was truncated.
                  </p>
                )}
                {report.errors.length > 0 && (
                  <ul className="mt-1 space-y-0.5">
                    {report.errors.map((e) => (
                      <li key={e} style={{ color: "var(--status-critical-text)" }}>
                        {e}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </div>
        )}
      </Panel>

      <Panel
        title={draft.id ? `Editing ${draft.name || "source"}` : "Add an API source"}
        subtitle="A GET endpoint, called on an interval by a background thread. Records land in api_feed_rows."
        actions={
          draft.id ? (
            <Button
              variant="ghost"
              onClick={() => {
                setDraft(EMPTY_SOURCE);
                setTest(null);
              }}
            >
              Cancel
            </Button>
          ) : undefined
        }
      >
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="block">
              <span className="text-[11px] font-medium" style={{ color: "var(--text-secondary)" }}>
                Name
              </span>
              <input
                value={draft.name}
                onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))}
                placeholder="Headcount feed"
                className={INPUT}
              />
            </label>
            <label className="block">
              <span className="text-[11px] font-medium" style={{ color: "var(--text-secondary)" }}>
                URL — GET only
              </span>
              <input
                value={draft.url}
                onChange={(e) => setDraft((d) => ({ ...d, url: e.target.value }))}
                placeholder="https://api.example.com/v1/positions"
                className={INPUT}
              />
            </label>
          </div>

          <div className="grid gap-3 sm:grid-cols-4">
            <label className="block">
              <span className="text-[11px] font-medium" style={{ color: "var(--text-secondary)" }}>
                Path to the records
              </span>
              <input
                value={draft.jsonPath}
                onChange={(e) => setDraft((d) => ({ ...d, jsonPath: e.target.value }))}
                placeholder="data.items"
                className={INPUT}
              />
            </label>
            <label className="block">
              <span className="text-[11px] font-medium" style={{ color: "var(--text-secondary)" }}>
                Key field
              </span>
              <input
                value={draft.keyField}
                onChange={(e) => setDraft((d) => ({ ...d, keyField: e.target.value }))}
                placeholder="id"
                className={INPUT}
                list="test-fields"
              />
              {test?.fields && test.fields.length > 0 && (
                <datalist id="test-fields">
                  {test.fields.map((f) => (
                    <option key={f} value={f} />
                  ))}
                </datalist>
              )}
            </label>
            <label className="block">
              <span className="text-[11px] font-medium" style={{ color: "var(--text-secondary)" }}>
                Every (seconds)
              </span>
              <input
                type="number"
                min={10}
                value={draft.intervalSeconds}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, intervalSeconds: Number(e.target.value) || 300 }))
                }
                className={INPUT}
              />
            </label>
            <div className="flex flex-col justify-end gap-1.5 pb-1">
              <label className="flex cursor-pointer items-center gap-2 text-[11.5px]">
                <input
                  type="checkbox"
                  checked={draft.enabled}
                  onChange={(e) => setDraft((d) => ({ ...d, enabled: e.target.checked }))}
                  className="h-3.5 w-3.5 cursor-pointer accent-[var(--series-1)]"
                />
                Polling enabled
              </label>
              <label className="flex cursor-pointer items-center gap-2 text-[11.5px]">
                <input
                  type="checkbox"
                  checked={draft.replaceEachRun}
                  onChange={(e) => setDraft((d) => ({ ...d, replaceEachRun: e.target.checked }))}
                  className="h-3.5 w-3.5 cursor-pointer accent-[var(--series-1)]"
                />
                Replace rows each run
              </label>
            </div>
          </div>

          <label className="block">
            <span className="text-[11px] font-medium" style={{ color: "var(--text-secondary)" }}>
              Request headers <span style={{ color: "var(--text-muted)" }}>(optional JSON object)</span>
            </span>
            <input
              value={draft.headersJson}
              onChange={(e) => setDraft((d) => ({ ...d, headersJson: e.target.value }))}
              placeholder={'{"X-Api-Key": "…"}'}
              className={INPUT}
            />
          </label>

          <div className="flex flex-wrap items-center gap-2">
            <Button variant="secondary" onClick={() => void testSource()} disabled={busy || !draft.url}>
              Test this endpoint
            </Button>
            <Button
              variant="primary"
              onClick={() => void saveSource()}
              disabled={busy || !draft.name.trim() || !draft.url.trim()}
            >
              {draft.id ? "Save changes" : "Add source"}
            </Button>
            <span className="text-[11px]" style={{ color: "var(--text-muted)" }}>
              Testing calls the endpoint and stores nothing.
            </span>
          </div>

          {test && (
            <div
              className="rounded-md p-3 text-[11.5px] leading-relaxed"
              style={{
                background: "var(--surface-2)",
                borderLeft: `3px solid ${test.ok ? "var(--accent-ink)" : "var(--status-critical)"}`,
              }}
            >
              <p className="font-medium" style={{ color: test.ok ? "var(--accent-ink)" : undefined }}>
                {test.ok ? "Reachable" : "Failed"}
                {test.statusCode > 0 && ` · HTTP ${test.statusCode}`} · {test.elapsedMillis} ms
                {test.records > 0 && ` · ${test.records} record(s) as ${test.shape}`}
              </p>
              <p style={{ color: "var(--text-secondary)" }}>{test.message}</p>
              {test.fields.length > 0 && (
                <p style={{ color: "var(--text-muted)" }}>Fields: {test.fields.join(", ")}</p>
              )}
              {test.preview && (
                <pre
                  className="scroll-x mt-1.5 rounded p-2 text-[10.5px] leading-snug"
                  style={{ background: "var(--surface-1)", color: "var(--text-secondary)" }}
                >
                  {test.preview}
                </pre>
              )}
            </div>
          )}
        </div>
      </Panel>

      <Panel
        title="API sources"
        subtitle="Each one is polled by a background thread on its own interval. api_feed_rows is a landing table — give a feed its own table once its shape has settled."
      >
        {!sources ? (
          <Skeleton rows={3} />
        ) : sources.length === 0 ? (
          <p className="text-[12px]" style={{ color: "var(--text-muted)" }}>
            No API sources are configured yet.
          </p>
        ) : (
          <div className="space-y-3">
            {sources.map((view) => {
              const s = view.source;
              return (
                <div
                  key={s.id}
                  className="rounded-lg p-3"
                  style={{ border: "1px solid var(--card-border)", background: "var(--surface-1)" }}
                >
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div className="min-w-0">
                      <h3 className="flex flex-wrap items-center gap-2 text-[13px] font-semibold">
                        {s.name}
                        {s.enabled ? (
                          <Badge tone="info" glyph="◆">
                            polling every {s.intervalSeconds}s
                          </Badge>
                        ) : (
                          <Badge tone="neutral">paused</Badge>
                        )}
                        {s.lastStatus && (
                          <Badge tone={s.lastStatus === "OK" ? "good" : "critical"} glyph={s.lastStatus === "OK" ? "●" : "▲"}>
                            {s.lastStatus}
                          </Badge>
                        )}
                      </h3>
                      <p className="mt-0.5 truncate text-[11px]" style={{ color: "var(--text-secondary)" }}>
                        {s.url}
                      </p>
                      <p className="text-[10.5px]" style={{ color: "var(--text-muted)" }}>
                        {s.jsonPath ? `path ${s.jsonPath}` : "path (root)"}
                        {s.keyField && ` · key ${s.keyField}`}
                        {s.replaceEachRun ? " · replaces each run" : " · appends"} ·{" "}
                        {view.rowsHeld.toLocaleString("en-IN")} row
                        {view.rowsHeld === 1 ? "" : "s"} held
                        {s.lastRunAt && ` · last run ${formatDateTime(s.lastRunAt)}`}
                      </p>
                      {s.lastMessage && (
                        <p
                          className="mt-1 text-[10.5px]"
                          style={{
                            color:
                              s.lastStatus === "OK"
                                ? "var(--text-muted)"
                                : "var(--status-critical-text)",
                          }}
                        >
                          {s.lastMessage}
                        </p>
                      )}
                    </div>
                    <div className="flex shrink-0 flex-wrap items-center gap-2">
                      <Button variant="primary" onClick={() => void runSource(view)} disabled={busy}>
                        Run now
                      </Button>
                      <Button variant="secondary" onClick={() => void showRows(view)}>
                        Show rows
                      </Button>
                      <Button
                        variant="secondary"
                        onClick={() => {
                          setDraft({
                            id: s.id,
                            name: s.name,
                            url: s.url,
                            headersJson: JSON.stringify(view.headers ?? {}) === "{}" ? "" : JSON.stringify(view.headers),
                            jsonPath: s.jsonPath ?? "",
                            keyField: s.keyField ?? "",
                            intervalSeconds: s.intervalSeconds,
                            enabled: s.enabled,
                            replaceEachRun: s.replaceEachRun,
                          });
                          setTest(null);
                          window.scrollTo({ top: 0, behavior: "smooth" });
                        }}
                      >
                        Edit
                      </Button>
                      <Button variant="danger" onClick={() => void deleteSource(view)} disabled={busy}>
                        Delete
                      </Button>
                    </div>
                  </div>

                  {rows[s.id] && (
                    <div className="mt-2.5">
                      {rows[s.id].length === 0 ? (
                        <p className="text-[11px]" style={{ color: "var(--text-muted)" }}>
                          Nothing landed for this source yet.
                        </p>
                      ) : (
                        <DataTable
                          headers={[{ label: "#", align: "right" }, "Key", "Fetched", "Record"]}
                          ariaLabel={`Recent rows from ${s.name}`}
                        >
                          {rows[s.id].map((row, i) => (
                            <tr key={`${row.record_index}-${i}`}>
                              <Td align="right">{row.record_index}</Td>
                              <Td>{row.record_key ?? "—"}</Td>
                              <Td>{formatDateTime(row.fetched_at)}</Td>
                              <Td>
                                <span className="block max-w-[32rem] truncate font-mono text-[10.5px]">
                                  {row.payload_json}
                                </span>
                              </Td>
                            </tr>
                          ))}
                        </DataTable>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </Panel>

      <Panel
        title="Approved headcount positions"
        subtitle="Backs the open-positions metric card"
        note="No requisition dataset was supplied in the Phase 1 extract, so this starts empty and the metric card reports itself as unavailable rather than showing a zero. Loading positions here — by file above, or from an API source — activates the card."
      >
        {positions.length === 0 ? (
          <p className="text-[12px]" style={{ color: "var(--text-secondary)" }}>
            No approved positions are configured.
          </p>
        ) : (
          <DataTable
            headers={[
              "Requisition",
              "Business unit",
              "Designation",
              { label: "Approved", align: "right" },
              { label: "Filled", align: "right" },
              "Target close",
              "Status",
            ]}
            ariaLabel="Open positions"
          >
            {positions.map((p) => (
              <tr key={p.id}>
                <Td>{p.requisitionId ?? p.id.slice(0, 8)}</Td>
                <Td>{p.vertical}</Td>
                <Td>{p.designation ?? "—"}</Td>
                <Td align="right">{p.approvedCount}</Td>
                <Td align="right">{p.filledCount}</Td>
                <Td>{p.targetCloseDate ? formatDate(p.targetCloseDate) : "—"}</Td>
                <Td>
                  <Badge tone={p.status === "OPEN" ? "info" : "neutral"}>{p.status}</Badge>
                </Td>
              </tr>
            ))}
          </DataTable>
        )}
      </Panel>
    </div>
  );
}

function Fact({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div
      className="rounded-md p-2.5"
      style={{ background: "var(--surface-2)", border: "1px solid var(--border-hairline)" }}
    >
      <p className="text-[10px] font-semibold tracking-wide uppercase" style={{ color: "var(--text-muted)" }}>
        {label}
      </p>
      <p className={`mt-0.5 truncate text-[12.5px] ${mono ? "font-mono text-[11px]" : ""}`} title={value}>
        {value}
      </p>
    </div>
  );
}
