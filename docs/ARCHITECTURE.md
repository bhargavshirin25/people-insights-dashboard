# Technical architecture

**Audience:** engineers working on or integrating with this codebase. For product/scope context
read [`OVERVIEW.md`](OVERVIEW.md) first; for the privacy and access-control rules and their
sign-off status read [`ACCESS-CONTROL.md`](ACCESS-CONTROL.md); for the source data's shape and
limits read [`DATA-NOTES.md`](DATA-NOTES.md); for the MySQL schema, indexing and the Mongo
migration history read [`MYSQL.md`](MYSQL.md). This document is the map that ties those together
and covers what they don't: package layout, request flow, the API surface, and deployment.

---

## 1. System at a glance

```
┌─────────────────┐        ┌──────────────────────────┐        ┌─────────────────┐
│   Browser        │        │  Next.js 15 (App Router)  │        │  Spring Boot 3.5 │
│  React 19 UI     │◀──────▶│  Server-side proxy         │◀──────▶│  Java 21 API      │
│  (:3000)         │  HTTP  │  /api/* → backend           │  HTTP  │  (:8080)          │
└─────────────────┘        └──────────────────────────┘        └────────┬────────┘
                                                                          │
                                                     ┌────────────────────┼─────────────────────┐
                                                     ▼                    ▼                     ▼
                                            ┌────────────────┐  ┌────────────────┐   ┌──────────────────┐
                                            │  MySQL 8.4      │  │ api.anthropic   │   │ Polled 3rd-party  │
                                            │  people_insights │  │ .com (Claude)   │   │ APIs (data-source) │
                                            └────────────────┘  └────────────────┘   └──────────────────┘
```

- **Frontend and backend are same-origin from the browser's point of view.** Next.js proxies
  `/api/*` to Spring Boot (`BACKEND_ORIGIN`, baked in at `next build` time — see §7), so the
  session cookie is first-party and no CORS relaxation exists anywhere in the stack.
- **The backend is the only thing that talks to MySQL or to Anthropic.** The frontend holds no
  credentials and makes no third-party calls itself.
- **MySQL is the sole system of record.** It replaced MongoDB Atlas on 17 Aug 2026 (§8; full
  detail in `MYSQL.md`); the Atlas database has since been dropped.

---

## 2. Backend package layout

```
backend/src/main/java/com/leadsquared/peopleinsights/
  config/      Typed @ConfigurationProperties (AppProperties, ClaudeProperties, ...),
               Spring Data JDBC Persistable callbacks, startup role/user seeding (DataSeeder)
  domain/      Records for the 7 HR Ops datasets + users, roles, BU assignments, audit
               events, retention actions, narratives, API sources, ingest runs
  repo/        Spring Data JDBC repositories — one per aggregate root
  ingest/      Apache POI readers that turn the HR Ops .xlsx workbooks into domain records
  migrate/     One-way MongoDB → MySQL importer (historical; the source no longer exists)
  security/    ScopeGuard, AccessPolicy, AccessScope, AuditService, Entra OIDC, the
               placeholder sign-in, SecurityConfig (the HTTP authorization matrix)
  metrics/     DatasetLoader + DatasetCache, and one service per dashboard view
               (RiskScoringService, ExitAnalyticsService, PerformanceService,
               LeaveAttendanceService, HeatMapService, CalendarService)
  ai/          ClaudeClient (HTTP client for /v1/messages), NarrativeService (figure-verified
               summaries), DashboardBrief (the assistant's fact sheet), ChatService
  datasource/  Ad-hoc file import (ImportService), the information_schema-driven table
               catalogue (TableCatalog), the polled API source poller (ApiSourceService)
  export/      Business review deck (PDF, via openhtmltopdf)
  web/         REST controllers — thin: parse the request, call ScopeGuard, call a service

frontend/
  app/(app)/   One route per view, inside the authenticated shell (app/(app)/layout.tsx)
  app/login/   The Microsoft sign-in page
  components/  Charts (Recharts), metric tiles, the shared filter bar, narrative panel,
               the Ask Robin chat widget
  lib/         API client (lib/api.ts), session context, filter context, formatting,
               view-data hooks (lib/useViewData.ts)
```

**The controller layer is deliberately thin.** A controller's job is: resolve the caller's scope
through `ScopeGuard`, parse the request into a `FilterSpec`, delegate to exactly one metrics or
domain service, and return its result. Business logic — risk scoring, exit theming, narrative
figure verification — lives in `metrics/` and `ai/`, not in `web/`, so it is unit-testable without
standing up HTTP.

---

## 3. The single enforcement point: `ScopeGuard`

This is the one structural decision the rest of the security model depends on, so it is worth
understanding before anything else in this codebase.

```java
// ScopeGuard.resolve — every dashboard read passes through here
if (!scope.allowsBu(canonical)) {
  audit.denied(scope, canonical, dataType, action, "BU outside assignment");
  throw new AccessDeniedForBuException(canonical, "...");
}
```

`ScopeGuard` is the **only** class that turns an HTTP request parameter into a business-unit
list. `DatasetLoader` and every metrics service accept a `ScopeGuard.Scoped` (or the `List<String>`
inside it) and nothing else — there is no method signature anywhere that accepts a raw BU string
from a controller. That means cross-BU isolation is a property of the type system, not a
convention someone has to remember to apply at each new endpoint.

`ScopeGuard.resolve(...)`:
1. Loads the caller's `AccessScope` — email, role, effective permissions and BU list, **resolved
   fresh on every request** from `AccessPolicy` (not cached on the session), so a permission
   revoked on `/access` takes effect on the caller's very next click.
2. Rejects outright if the scope has no data-view permission at all (the fixed `ADMIN` tier, or a
   custom role built with no view ticked).
3. Canonicalises the requested BU case-insensitively against the configured list
   (`dashboard.business-units`); an unrecognised identifier is treated as **unauthorised**, not as
   "no rows" — the response is a `403`, never an empty `200`, so a probe for another BU's data is
   distinguishable from a genuinely empty result.
4. Writes exactly one audit event, granted or denied, in the same call that makes the decision —
   so a read cannot be un-audited by omission.

`resolveIndividual(...)` additionally rejects anything below HRBP, for the individual-grain
endpoints (the at-risk register, named leave lists, the HR calendar's per-event view).

This is enforced twice, deliberately: once at the HTTP layer in `SecurityConfig` (a *permission*
check — can this caller reach this endpoint at all) and once inside the service call via
`ScopeGuard` (a *business-unit* check — which rows can this specific request return). A hidden
nav link on the frontend is convenience only; both checks run server-side regardless of what the
UI shows.

---

## 4. Authorization model

Three concepts, defined in `security/` and `domain/`:

- **`Role`** — a fixed enum (`HRBP`, `HR_HEAD`, `CHRO`, `VIEWER`, `ADMIN`) carried on a user
  record. Since custom roles were introduced it is a **label only**, written into the audit trail
  for readability — it grants nothing by itself.
- **`Permission`** — 15 discrete permissions in 4 groups: which views open (`VIEW_OVERVIEW`,
  `VIEW_RISK`, `VIEW_EXIT`, `VIEW_PERFORMANCE`, `VIEW_LEAVE_ATTENDANCE`, `VIEW_HEATMAP`,
  `VIEW_AUDIT`), data grain (`SEE_INDIVIDUAL_PII`, `SEE_COMPENSATION`), actions
  (`USE_ASSISTANT`, `EXPORT_DECK`, `LOG_RETENTION_ACTION`, `EDIT_NARRATIVE`), and administration
  (`MANAGE_CONFIG`, `MANAGE_ACCESS`).
- **`CustomRole`** — a name, a permission set, a business-unit scope, and the email addresses that
  hold it. Membership is keyed by email rather than by user id, so a role can be granted to
  someone who has never signed in — it applies the first time they do. `AccessPolicy.resolve(...)`
  unions every role an address holds, both permissions and business units.

Two roles are seeded at every startup (`DataSeeder`), and both are re-provisioned defensively so
an administrator's later edits to *other* roles are never touched:

| Role | Permissions | Members | Deletable? |
|---|---|---|---|
| **Full access** | Rewritten to *every* permission on every start | `hr.automation@leadsquared.com`, `nalamati.shirin@leadsquared.com` (re-added only if the member list was emptied) | No — and `MANAGE_ACCESS` cannot be stripped from it, so there is no click sequence that locks everyone out of `/access` |
| **Data source manager** | `MANAGE_CONFIG` only | None | Yes — it is an ordinary role; an empty copy simply returns at next start |

**Enforcement of permissions happens in exactly one place** — the `authorizeHttpRequests` block
in `SecurityConfig` (§6) — matched by URL prefix, narrowest-first. A new endpoint added under an
existing prefix inherits that prefix's permission automatically; there is no per-controller
`@PreAuthorize` to forget.

---

## 5. Request lifecycle

```
Browser
  │  fetch('/api/dashboard/overview?bu=Engineering', {credentials:'same-origin'})
  ▼
Next.js rewrite                     (frontend/next.config.mjs — /api/* → BACKEND_ORIGIN)
  ▼
Spring Security filter chain        (SecurityConfig)
  │  • session lookup, CSRF check on writes (double-submit cookie: XSRF-TOKEN → X-XSRF-TOKEN)
  │  • authorizeHttpRequests: does this permission set cover /api/dashboard/**?  (VIEW_OVERVIEW)
  ▼
DashboardController.overview(bu, filters)
  ▼
ScopeGuard.resolve("Engineering", "HEADCOUNT", "VIEW_OVERVIEW")
  │  • re-resolves AccessScope from AccessPolicy (fresh, not session-cached)
  │  • canonicalises "Engineering", checks scope.allowsBu(...)
  │  • writes one AuditEvent (GRANTED or DENIED) — request stops here on DENIED
  ▼
DatasetLoader.load(scoped, filterSpec)
  ▼
DatasetCache.get(key = generation + businessUnits + label + asOf + filters, loader)
  │  • cache hit  → return the shared Dataset instantly
  │  • cache miss → assemble(): 6 repository reads issued concurrently (employees,
  │    compensation, leave balances, attendance months, eNPS, exits), joined by employee id
  ▼
MetricsService (one per view) computes cards / register / themes over the Dataset
  ▼
JSON response, Cache-Control: no-store
```

Two properties worth calling out because they are easy to get backwards:

- **Authorization and audit happen before the cache is ever consulted.** `DatasetCache`'s key
  includes the authorised BU list (which can only have come from `ScopeGuard`), so a cache hit
  cannot serve one caller's data to a caller with a different scope — but it *cannot* skip the
  authorization step, because the guard runs first regardless of cache state.
- **A dashboard "page load" is one `Dataset` assembly shared by every card and chart on that
  page**, not one query per widget. The overview page alone reads from three services against a
  single loaded `Dataset`.

---

## 6. HTTP authorization matrix

From `SecurityConfig.filterChain(...)` — the full list of what the API exposes and what it
requires. Order is significant (first match wins); reproduced here in the order it's declared.

| Path | Method | Permission required | Controller |
|---|---|---|---|
| `/api/auth/login`, `/api/auth/session`, `/api/auth/microsoft` | any | — (public) | `AuthController` |
| `/actuator/health` | any | — (public) | — |
| `/api/access/**` | any | `MANAGE_ACCESS` | `AccessController` |
| `/api/admin/**`, `/api/data-source/**` | any | `MANAGE_CONFIG` | `AdminController`, `DataSourceController` |
| `/api/audit/**` | any | `VIEW_AUDIT` | `AuditController` |
| `/api/risk/actions/**` | `POST` | `LOG_RETENTION_ACTION` | `RiskController` |
| `/api/risk/**` | any | `VIEW_RISK` | `RiskController` |
| `/api/insights/exit` | any | `VIEW_EXIT` | `InsightsController` |
| `/api/insights/performance` | any | `VIEW_PERFORMANCE` | `InsightsController` |
| `/api/insights/leave-attendance` | any | `VIEW_LEAVE_ATTENDANCE` | `InsightsController` |
| `/api/insights/compensation-bands` | any | `SEE_COMPENSATION` | `InsightsController` |
| `/api/dashboard/heatmap` | any | `VIEW_HEATMAP` | `DashboardController` |
| `/api/dashboard/**` | any | `VIEW_OVERVIEW` | `DashboardController` |
| `/api/narrative/regenerate`, `/api/narrative/edit` | `POST` | `EDIT_NARRATIVE` | `NarrativeController` |
| `/api/narrative/**` | any | `VIEW_OVERVIEW` | `NarrativeController` |
| `/api/chat/**` | any | `USE_ASSISTANT` | `ChatController` |
| `/api/export/**` | any | `EXPORT_DECK` | `ExportController` |
| anything else | any | authenticated | `/api/auth/logout` etc. |

Unauthenticated access to anything not `permitAll` returns `401`; authenticated but
under-permissioned access returns `403`. Both are handled centrally
(`exceptionHandling(...)` in `SecurityConfig`), and a rejected CSRF token is deliberately
reported with a distinct message rather than as a permission error, since it's a client bug
category, not an access decision.

### Full endpoint inventory

| Controller | Endpoints |
|---|---|
| `AuthController` | `GET /api/auth/session`, `POST /api/auth/microsoft`, `POST /api/auth/logout` |
| `DashboardController` | `GET /overview`, `GET /heatmap`, `GET /calendar`, `GET /filter-options`, `POST /saved-filters` |
| `RiskController` | `GET /register`, `POST /actions/{employeeId}`, `GET /actions`, `GET /detail/{employeeId}` |
| `InsightsController` | `GET /exit`, `GET /performance`, `GET /leave-attendance`, `GET /compensation-bands` |
| `NarrativeController` | `GET /`, `POST /regenerate`, `POST /edit` |
| `ChatController` | `GET /availability`, `POST /` |
| `AccessController` | `GET /catalog`, `GET /roles`, `POST /roles`, `DELETE /roles/{id}`, `POST /roles/{id}/members`, `DELETE /roles/{id}/members`, `GET /people` |
| `AuditController` | `GET /recent`, `GET /anomalies`, `GET /summary` |
| `AdminController` | `GET /open-positions`, `POST /open-positions`, `DELETE /open-positions/{id}`, `GET /data-status`, `POST /refresh-data` |
| `DataSourceController` | `GET /tables`, `POST /import`, `GET /api-sources`, `POST /api-sources`, `DELETE /api-sources/{id}`, `POST /api-sources/test`, `POST /api-sources/{id}/run`, `GET /api-sources/{id}/rows` |
| `ExportController` | `GET /deck`, `GET /csv` |

(All paths are relative to the controller's `@RequestMapping` root, e.g. `RiskController`'s
`GET /register` is `GET /api/risk/register`.)

---

## 7. Frontend

- **Next.js 15 App Router**, React 19, TypeScript, Tailwind v4. No client-side data-fetching
  library beyond `fetch` — `lib/api.ts` wraps it with CSRF header injection, `credentials:
  'same-origin'`, `cache: 'no-store'`, and a typed `ApiError` (`isAccessDenied`,
  `isUnauthenticated` helpers so a component can branch on a `403` vs. a genuine fault).
- **CSRF**: Spring Security issues a `XSRF-TOKEN` cookie; every non-`GET` request reads it and
  sends it back as `X-XSRF-TOKEN` (double-submit pattern). This only works because frontend and
  backend are same-origin through the proxy — there is no cross-origin credentialed request
  anywhere in the app.
- **`BACKEND_ORIGIN`** is a Next.js *build-time* arg, not a runtime env var — Next bakes rewrite
  destinations into the build at `next build`, which is why the Dockerfile takes it as `ARG` and
  why changing it means rebuilding the frontend image, not just restarting the container.
- **Filter state** (`lib/filters.tsx`) is shared across every view via context and persisted
  server-side per user, so it survives a different browser/device.
- **Theme**: `prefers-color-scheme` until a manual toggle pins a choice to `localStorage`; a
  small inline script in `<head>` applies a stored choice before first paint to avoid a flash.

---

## 8. Data layer

Full detail — schema, indexing decisions measured against the live query stream, and the N+1
issue below — lives in [`MYSQL.md`](MYSQL.md). Summary for orientation:

- **MySQL 8.4**, `people_insights` schema, 19 tables. 9 hold the HR Ops datasets and their child
  rows (`employees` + `employee_pms_cycles`, `compensation`, `leave_balances`,
  `leave_transactions`, `attendance_months` + `attendance_days`, `enps_responses`, `exit_records`
  + `exit_record_themes`); the rest hold users, BU assignments, audit events, retention actions,
  narratives, requisitions and ingest history.
- **`schema.sql` runs on every start** (`spring.sql.init.mode: always`), all statements
  `IF NOT EXISTS` — a start never alters an existing table, so a column change needs its own
  migration, not an edit to that file. MySQL has no `CREATE INDEX IF NOT EXISTS`, so new indexes
  on a live database need a one-off `ALTER TABLE`.
- **Spring Data JDBC**, not JPA/Hibernate — aggregates are loaded whole, which is why
  `attendance_days` (~903,000 day marks) is deliberately kept *outside* the `attendance_months`
  aggregate as a plain table: mapping it as a child collection would drag the full per-day
  register into every dashboard read. The same reasoning kept `leave_transactions` out of the
  dashboard's read path entirely (unplanned absence is measured from the attendance register
  instead — see `DATA-NOTES.md`).
- **MySQL cannot generate a `VARCHAR` primary key**, so ids are assigned in the application. Since
  that removes Spring Data's usual null-id "is this new?" signal, `AppUser`, `BuAssignment` and
  `NarrativeDoc` implement `Persistable` (flipped to "loaded" by an `AfterConvertCallback` in
  `JdbcPersistenceConfig`); the immutable dataset records are never updated and go through
  `Store.insert(...)`, which fails loudly on a duplicate id instead of silently no-op-updating.
- **Known, understood, unfixed:** an org-wide dashboard read issues ~7,000+ queries because
  Spring Data JDBC loads each aggregate's child collections with one query per parent row
  (`employee_pms_cycles`, `exit_record_themes`). Server-side time is ~1.3s; the rest is round-trip
  overhead. The fix — load each child table once with `WHERE parent_id IN (...)` in
  `DatasetLoader` instead of through the aggregate mapping — is scoped but not done. See
  `MYSQL.md` § "What actually costs the time" for the measured before/after.
- **Migration history**: MongoDB Atlas → MySQL on 17 Aug 2026, Atlas dropped 18 Aug 2026 after
  verification. `migrate/MongoToMysqlMigration` remains in the tree but its source database no
  longer exists — running it now would wipe every MySQL table and refill it with nothing, passing
  its own count check trivially (0 == 0). It is dead code pending a deliberate cleanup PR (also
  removing the `mongodb-driver-sync` dependency and `migration.mongodb.*` properties).

---

## 9. Performance: the dataset cache

`DatasetCache` (`metrics/DatasetCache.java`) is what takes an org-wide read from ~11s to
under 0.1s, and is the single highest-leverage piece of infrastructure in the backend. Three
properties make it *correct*, not just fast:

1. **The cache key is the whole identity of the data**: ingest generation + authorised BU list +
   label + as-of date + filter spec. Since the BU list can only ever come from `ScopeGuard`, a
   cached entry cannot be served to a caller whose scope differs from the one that produced it.
2. **Every entry is stamped with the ingest generation**, re-read from `ingest_runs` at most once
   per `dashboard.dataset-cache-revalidate-seconds` (default 30s). A new ingest changes the
   generation and every existing entry stops matching at once; `IngestService` also clears the
   cache outright on a re-ingest this process performed itself.
3. **One load per key, not one per waiter** — a second concurrent request for a key already being
   assembled blocks on the first `CompletableFuture` rather than issuing a duplicate ~20s read.

Eviction is LRU by last-read time, bounded by `dashboard.dataset-cache-entries` (default 16;
`0` disables caching entirely, useful for isolating a suspected staleness bug).

**This is also an open compliance question, not just an engineering decision** — see
`ACCESS-CONTROL.md` §6. The access rules as originally read forbid shared, cross-session state
holding identifiable employee data; the cache cannot widen access (its key enforces that
structurally) but it *is* shared state of exactly that kind, and needs HR Ops sign-off, a rule
amendment, or a narrower design (aggregates-only cache, or SQL-side aggregation now that the data
layer is local rather than remote).

Other contributors to the same performance story: the six per-scope reads in
`DatasetLoader.assemble(...)` are issued concurrently via `CompletableFuture`; the heat map builds
its per-BU rows concurrently; attendance is loaded only for the months a metric actually reads,
never the per-day register.

---

## 10. AI integration

Two features, one thin HTTP client (`ai/ClaudeClient.java`, talking to `/v1/messages` directly —
no SDK dependency, since a second caller didn't justify one).

### Narrative (`NarrativeService`)

- Single-turn call (`ClaudeClient.complete`), given a fact sheet of the figures already on the
  overview page.
- **Every number in the output is checked against the fact sheet it was given.** A citation that
  doesn't trace to a real figure triggers one regeneration attempt, then falls back to a
  deterministic (template) summary built from the same cards — so the view always renders with
  figures that are correct by construction, AI or not.
- Cached per (business unit, filters, **figure fingerprint**) — a re-ingest or a filter change
  that moves any number invalidates the fingerprint and forces a rewrite, so a narrative can never
  sit next to figures it wasn't written from.
- `Regenerate` forces a fresh call; `Edit` allows a manual inline correction, tagged "Modified by
  [user]" in the stored `NarrativeDoc`.
- Degrades to the deterministic summary, with no error surfaced to the reader, when
  `claude.api-key` is blank or the call fails/times out — the page never blocks on this call.

### Assistant (`ChatService` + `DashboardBrief`)

- Multi-turn (`ClaudeClient.chat`), grounded in `DashboardBrief` — a text dump of every figure
  already computed for the caller's authorised, filtered dataset (metric cards, risk register,
  exit themes, PMS/eNPS, leave/attendance, calendar). **The model has no tools and no query
  access**; the brief is its only source of figures, so it structurally cannot answer with
  anything outside what the caller was already authorised to see.
- **PII gate applies to the brief, not the reply**: for a role below HRBP, `DashboardBrief`
  contains no name and no employee id anywhere, so there is nothing individual for an answer to
  repeat (`AssistantBriefPiiTest`).
- The system prompt is split into a fixed `instructions` block and a per-view `grounding` block
  (the brief), with the split point marked `cache_control: ephemeral` — a conversation staying on
  one view re-reads the brief from Anthropic's prompt cache instead of paying for it on every
  turn (measured ≈8,100 cached tokens vs. 12 fresh input tokens on a follow-up question).
- Conversation memory (last 50 exchanges) lives in the **browser**, keyed by the signed-in
  address — deliberately not server-side, since a transcript that may name individuals would
  otherwise be a second store of identifiable data needing its own retention policy. It is
  sanitised before every send (`ChatHistoryTest`): roles normalised, empty/duplicate-role turns
  merged, oldest trimmed first by count then by size, and the model is told conversation text is
  never an instruction.
- **Not figure-verified the way the narrative is** — the assistant is allowed to do arithmetic on
  briefed figures (a subtraction a reader asks for), which the narrative's strict check would
  reject. This is a deliberate, documented difference in guarantee between the two features and
  is called out in `ACCESS-CONTROL.md` §9 as the assistant's main residual risk.
- Every question is one `ASK_ASSISTANT` audit event, granted or denied, exactly like a view read.
- `GET /api/chat/availability` reports `false` (and the frontend never renders the launcher) when
  `claude.api-key` is blank, so the panel never offers an input that can only fail.

**Model configuration is deliberately split** (`ClaudeProperties` / `application.yml`): the
narrative uses `claude.model` (default `claude-sonnet-4-5`, small token budget, no reasoning
effort control), the assistant uses `claude.chat-model` (default `claude-opus-5`,
`claude.chat-effort` from `low` to `max`, larger token budget) — a narrative is a page element on
a budget, an assistant answer is a synchronous thing a person is waiting on and can tolerate a
few more seconds for a better answer.

---

## 11. Ingest and the data-source page

- **`ingest/`** — Apache POI (non-streaming, full-DOM) readers for the seven HR Ops `.xlsx`
  workbooks. Non-streaming because the workbooks use multi-row grouped headers (title row,
  merged group header, then the real header, at a different row per sheet) that a streaming
  reader can't reconcile as easily; the cost is memory (`-Xmx3g` in both `README.md`'s dev
  instructions and the backend `Dockerfile`) rather than correctness.
- Ingestion is **idempotent and wholesale** — it replaces the people tables entirely on each run,
  because the workbooks are a full snapshot, not a delta. It never touches configuration tables
  (users, roles, BU assignments, audit trail), so re-running it cannot wipe access grants.
- `IngestRunner` / `IngestService` record one `IngestRun` per attempt (row counts, warnings,
  reporting date produced), surfaced on `/data-source`, and clear `DatasetCache` on completion.
- **`/data-source`** is also where an administrator can (a) import a CSV/`.xlsx` into any table
  discovered from `information_schema` (headers fuzzy-matched to columns; `app_users`,
  `custom_roles`, `bu_assignments`, `audit_events` and the narrative/ingest tables are excluded
  from the importable list outright), and (b) configure a polled GET API source, landing rows into
  `api_feed_rows`. Both paths are `MANAGE_CONFIG`-gated and produce their own audit events
  (`DATA_SOURCE` type) distinct from a dashboard read.
- A configured poll target is fetched **by the server**, so it can reach anything the server can
  reach — this is why the feature sits behind `MANAGE_CONFIG` and why
  `dashboard.data-source.allow-private-hosts` exists as the one knob to close it off in an
  environment where that's not acceptable.

---

## 12. Configuration reference

Key properties from `application.yml` (see the file for full inline rationale on each):

| Property | Default | Notes |
|---|---|---|
| `dashboard.as-of-date` | `2026-07-31` | Anchors every period-relative metric; blank tracks the latest ingested attendance month |
| `dashboard.dev-login-enabled` | `true` | Gates the placeholder sign-in — **must be false, with `sso` profile active, before real data** |
| `dashboard.dataset-cache-entries` | `16` | `0` disables `DatasetCache` |
| `dashboard.dataset-cache-revalidate-seconds` | `30` | Max staleness window for detecting a re-ingest from another process |
| `dashboard.audit-retention-days` | `90` | Mirrored by the MySQL scheduled event — see `MYSQL.md` |
| `dashboard.anomaly-denied-threshold` / `-window-minutes` | `5` / `60` | Repeated-denial alerting threshold |
| `dashboard.data-source.polling-enabled` | `true` | Master switch for the API-source poller |
| `dashboard.data-source.allow-private-hosts` | `true` | Whether a polled/imported URL may reach localhost or the internal network |
| `claude.api-key` | *(blank)* | Blank cleanly disables both the narrative (falls back to deterministic) and the assistant (panel hidden) |
| `claude.model` / `claude.chat-model` | `claude-sonnet-4-5` / `claude-opus-5` | Narrative vs. assistant models, independently configurable |
| `claude.chat-effort` | `medium` | `low`..`max`; assistant reasoning depth vs. latency |
| `server.servlet.session.timeout` | `30m` | Inactivity timeout before re-authentication |

Profiles: `local` (default dev), `sso` (activates Entra OIDC, disables the placeholder sign-in —
see `application-sso.yml`). Environment overrides for secrets follow the `${ENV_VAR:default}`
pattern throughout — `MYSQL_URL`/`MYSQL_USER`/`MYSQL_PASSWORD`, `CLAUDE_API_KEY`,
`AZURE_CLIENT_ID`/`AZURE_CLIENT_SECRET`/`AZURE_ISSUER_URI`, `INGEST_DIR`.

---

## 13. Deployment

- **Local dev**: `dev.sh` starts the MySQL container (must already exist — see `MYSQL.md` for the
  `docker run`), then runs the backend (`mvn spring-boot:run -Dspring-boot.run.profiles=local`)
  and frontend (`npm run dev`) concurrently, prefixing their output `[api]` / `[web]`.
- **Docker Compose** (`docker-compose.yml`): three services — `mysql` (health-checked before the
  backend starts), `backend` (multi-stage Maven build → `eclipse-temurin:21-jre`, `-Xmx3g` for the
  same POI in-memory-parse reason as local dev), `frontend` (multi-stage Node 20 Alpine build,
  `BACKEND_ORIGIN` passed as a build arg pointing at the `backend` service's Docker DNS name).
  Workbook directory is bind-mounted into the backend container via `WORKBOOK_DIR`.
- **Ports**: backend `8081→8080`, frontend `3000→3000`, MySQL `3307→3306` by default (host-side
  ports are overridable env vars, to coexist with a local non-Docker MySQL on `3306`).
- Secrets (`MYSQL_ROOT_PASSWORD`, `CLAUDE_API_KEY`) are read from a `.env` file at the compose
  root (gitignored) — never bake a real key into `docker-compose.yml` or an image layer.

---

## 14. Tests

`cd backend && mvn test` — 70 tests. Organised around what would be most damaging to get wrong,
not around class-per-test-class coverage:

| Suite | What it locks down |
|---|---|
| `ScopeGuardTest` | Cross-BU isolation, case/unknown-BU handling, "ALL" narrowing to assignment, unassigned-address denial, config-only role denial, individual-grain gating, universal audit writes |
| `AccessPolicyTest` | No role ⇒ no access; a role decides outright rather than layering on a tier; two roles union; unknown BU dropped; membership is case-insensitive |
| `SeededRolesTest` | Full-access role always holds *every* current permission (not a frozen list); data-source role holds config only; neither starts with unexpected members; an admin's existing edit to either survives a restart |
| `NarrativeVerificationTest` | An invented figure is caught; a rounded restatement of a real one is allowed; formatting differences don't false-positive |
| `AssistantBriefPiiTest` | The assistant's brief carries no name/employee id below HRBP |
| `CalendarPiiTest` | Individual calendar events withheld below HRBP while per-type counts stay accurate |
| `ChatHistoryTest` | Conversation sanitisation: 50-exchange window, oldest-first trim, merged same-role turns, discarded fake system turns |
| `DataSourceTest` | CSV/JSON import edge cases: quoted delimiters, ragged rows, BOM handling, fuzzy header matching, polling-interval correctness |
| `DatasetCacheTest` | A cached dataset cannot cross scopes; a new ingest drops it |
| `CsvExportServiceTest` | Compensation columns exist only for `SEE_COMPENSATION`; one row per employee, exited employees included with no stale risk score; header/row field counts always match; a formula-like value (`=`, `+`, `-`, `@`) is neutralised before it reaches the file |

---

## 15. Known technical debt (as of this writing)

Tracked here so it doesn't have to be rediscovered by reading the code:

1. **N+1 child loads on org-wide reads** (§8) — scoped fix identified, not implemented.
2. **`DatasetCache` compliance status is open** (§9) — needs an explicit HR Ops decision, not an
   engineering one.
3. **Placeholder sign-in is a live authentication bypass** until Entra credentials are supplied —
   blocking for any environment with real data (`ACCESS-CONTROL.md` §11, item 7 in its sign-off
   table).
4. **BU-to-HRBP mapping is a seeded default**, not the confirmed real mapping.
5. **`migrate/MongoToMysqlMigration` is dead code** whose source database has been dropped;
   running it would silently empty every table. Safe to delete along with the
   `mongodb-driver-sync` dependency once the cutover is considered final.
6. **Compa ratio as a risk factor** is a one-line removal (`RiskScoringService`) if HR Ops decides
   it's too sensitive to surface even in aggregate/relative form.
