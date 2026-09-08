# Robin Insights Dashboard

BU-level people metrics, attrition risk and exit intelligence for HR business partners at
LeadSquared. Next.js frontend, Spring Boot backend, MySQL data layer, Claude for narrative
summaries.

- **Project overview** — [`docs/OVERVIEW.md`](docs/OVERVIEW.md). What this is, who uses it, and what
  is and isn't production-ready yet.
- **Technical architecture** — [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). System design, package
  layout, request flow, the API surface, for engineers working on the codebase.
- **Access control and privacy architecture** — [`docs/ACCESS-CONTROL.md`](docs/ACCESS-CONTROL.md).
  This is the document HR Ops signs off before go-live.
- **What the data does and does not support** — [`docs/DATA-NOTES.md`](docs/DATA-NOTES.md). Read this
  before questioning a number.

---

## Running it

Prerequisites: JDK 21, Node 20+, and Docker for the MySQL container. See
[`docs/MYSQL.md`](docs/MYSQL.md) for the container command, the schema, and index notes. The data layer
moved from MongoDB Atlas to local MySQL on 17 Aug 2026, and the Atlas database was dropped on 18 Aug
once the copy was verified — **MySQL is now the only live copy**, with a `mongodump` archive kept at
`~/Downloads/mongo-people-insights-backup-2026-08-18/`.

### 1. Load the HR Ops workbooks into MySQL

The seven datasets ship as `.xlsx`, not CSV, and the loader reads them directly. Run this once (and
again whenever the workbooks change):

```bash
cd backend
JAVA_HOME=/path/to/jdk-21 mvn spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--ingest" \
  -Dspring-boot.run.jvmArguments="-Xmx3g"
```

The run prints a per-table row count and exits. It writes to the `people_insights` schema, which is
dedicated to this application.

Expected output on the supplied extract:

| Table | Rows |
|---|---|
| `employees` (+ `employee_pms_cycles`) | 5,000 (9,904) |
| `compensation` | 5,000 |
| `leave_balances` | 5,000 |
| `leave_transactions` | 32,689 |
| `attendance_months` (+ `attendance_days`) | 29,960 (903,030) |
| `enps_responses` | 5,000 |
| `exit_records` (+ `exit_record_themes`) | 1,476 (2,887) |

Ingestion is idempotent and replaces the people tables wholesale, because the workbooks are a full
snapshot rather than a delta. It never touches the configuration tables, so re-running it does not
wipe user roles, BU assignments, logged retention actions or the audit trail.

Already-migrated data does not need re-ingesting: everything that was in Atlas is already in MySQL.
Run this only when the workbooks themselves change.

`-Xmx3g` matters: the attendance workbook is roughly 915,000 cells and Apache POI holds the sheet in
memory while parsing.

### 2. Start the backend

```bash
cd backend
JAVA_HOME=/path/to/jdk-21 mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Serves on `http://localhost:8080`.

### 3. Start the frontend

```bash
cd frontend
npm install
npm run dev      # or: npm run build && npm run start
```

Open `http://localhost:3000`. Next proxies `/api/*` to the backend, so the session cookie stays
same-origin and no CORS relaxation exists anywhere.

### 4. Sign in

Sign-in is **Microsoft only**. The page offers one button, and there is no password path — no account
holds a password hash and the endpoint that checked one has been removed.

Corporate SSO is the required mechanism and the Entra integration is implemented, but this instance has
no tenant credentials — so until it does, that button is a **placeholder that performs no credential
check**: it signs you in as `dashboard.placeholder-sign-in-email`, currently
`nalamati.shirin@leadsquared.com` (Nalamati Bhargav Shirin). The startup log warns about it, and the
audit trail records it as a placeholder sign-in rather than as SSO. Section 11 of
[`docs/ACCESS-CONTROL.md`](docs/ACCESS-CONTROL.md) treats it as the authentication bypass it is; it must
not reach an environment with real data.

To switch to real SSO, fill in `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET` and `AZURE_ISSUER_URI` and run
with `--spring.profiles.active=local,sso`. That profile disables the placeholder, and the same button
starts the real handshake.

Two accounts are provisioned, both in the built-in **Full access** role — every permission, every
business unit:

| Account | Access |
|---|---|
| `nalamati.shirin@leadsquared.com` | Full access |
| `hr.automation@leadsquared.com` | Full access |

Nothing else is seeded. The demonstration accounts one per tier are gone, along with the tier defaults
that gave them their access: **a role granted on `/access` is now the only source of access**, and an
address no role names reaches nothing at all.

---

## The reporting date

The extract's attendance register ends **31 July 2026**. Every period-relative metric — "last 30
days", month-on-month, rolling three-month attrition — is anchored on that date rather than on the
wall clock, because a wall-clock anchor would make the entire dashboard empty. The anchor is
`dashboard.as-of-date`; leave it blank to track the latest attendance month found at ingest
automatically.

---

## Layout

```
backend/
  src/main/java/com/leadsquared/peopleinsights/
    config/     Properties, JDBC persistence callbacks, seed data
    domain/     The seven datasets plus users, BU assignments, audit, actions, narratives
    ingest/     Apache POI readers for the HR Ops workbooks
    migrate/    One-way MongoDB -> MySQL importer (on demand)
    security/   ScopeGuard, AccessPolicy, audit trail, Entra OIDC, placeholder sign-in
    metrics/    Dataset loader and one service per dashboard view
    ai/         Claude client, the narrative generator with figure verification, and the assistant
    datasource/ File import, the table catalogue, and the polled API sources
    export/     Business review deck (PDF)
    web/        REST controllers
frontend/
  app/(app)/    One route per view, inside the authenticated shell
  components/   Charts, metric tiles, filter bar, narrative panel, assistant panel
  lib/          API client, session and filter contexts, formatting
docs/           Access-control architecture and data notes
```

### How a request flows

```
Browser ──▶ Next proxy ──▶ Controller ──▶ ScopeGuard.resolve(bu) ──▶ DatasetLoader ──▶ MySQL
                                              │                          │
                                              ├─ writes an audit event   └─ takes an authorised
                                              └─ throws 403 on a               BU list only,
                                                 cross-BU attempt              never a raw param
```

`ScopeGuard` is the only place that turns a request parameter into a BU list. Services accept the
resolved list and nothing else, so no code path can read a BU the caller was not granted. See
[`docs/ACCESS-CONTROL.md`](docs/ACCESS-CONTROL.md).

---

## Views

| View | Route | Notes |
|---|---|---|
| BU overview | `/` | Six metric cards above the fold with MoM movement, AI narrative, 30-day HR calendar, one-click PDF deck export, filtered CSV export |
| Attrition risk register | `/risk` | Ranked, with the top three contributing factors per employee and retention-action logging |
| Exit analysis | `/exit` | Themes, exit types, tenure bands, sentiment trend, anonymised verbatims filterable by theme |
| Performance & engagement | `/performance` | Three PMS cycles, promotion rate, eNPS with theme breakdown, PMS-vs-engagement flags, high performers never promoted |
| Leave & attendance | `/leave-attendance` | Leave utilisation, team attendance health, absence anomaly flags, burnout and unplanned-absence lists |
| Org heat map | `/heatmap` | HR Head and CHRO only — every BU on the same metrics |
| Audit trail | `/audit` | Access events, anomalous-access alerts, 24-hour summaries |
| Access | `/access` | Custom roles: what each may reach, by checkbox, and the addresses that hold it |
| Data Source | `/data-source` | Workbook ingest, file imports into any table, polled API sources, approved headcount |

All views share one filter state (grade, location, tenure range, period), so a filter change moves
every card and chart together. The selection persists per user, server-side, so it survives a
different browser.

The header carries a **light/dark switch** at the top right, sun or moon depending on which way it
will go. Until it is used the dashboard follows the operating system through `prefers-color-scheme`;
one click pins the choice in `localStorage` and it wins in both directions from then on. A small
script in the document head applies a stored choice before the first paint, so a reader who chose dark
does not get a white flash on every load. Below the medium breakpoint the navigation and the identity
block collapse into a menu button beside it.

---

## The AI narrative

Each BU view carries a 3–5 sentence narrative covering metric movements, anomalies with a severity
level, and what needs attention.

The brief requires that every figure cited be traceable to a metric card on the same view. A prompt
instruction cannot guarantee that, so generated text is **verified**: every number in the output must
also appear in the fact sheet the model was given. A narrative citing a number from nowhere is
regenerated once and then replaced by a deterministic summary built from the same cards. The figures
the narrative is permitted to cite are listed in the UI behind "Show the figures this summary may
cite", so a reader can check the claim rather than take it on trust.

A narrative is cached against its business unit, its filters, **and a fingerprint of the figures**.
If a re-ingest or a filter moves any number, the fingerprint changes and the narrative is rewritten
— it can never sit beside figures it was not written from. `Regenerate` forces a fresh one; `Edit`
allows inline changes and tags the result "Modified by [user]".

A blank `claude.api-key` is a supported state: the deterministic summary is used instead, and the
view still renders with figures that are correct by construction.

---

## Access: roles you define

`/access` is where access is granted. A **role** is a name, a set of permissions ticked from a catalogue,
a business-unit scope, and the email addresses that hold it.

The catalogue has fifteen permissions in four groups — which **views** open (overview, risk, exit,
performance, leave, heat map, audit), how much **data grain** they show (individual employee data,
compensation), what **actions** are allowed (assistant, deck export, retention actions, editing the AI
summary), and the two **administration** surfaces (configuration, managing access itself).

Three properties are worth knowing before you use it:

- **A role is the only source of access.** There are no tier defaults and no implicit access: an address
  no role names reaches nothing at all, whatever the `role` column on its user record says. That column is
  a label carried into the audit trail and grants nothing.
- **Grants can precede arrival.** Membership is an email address, not a user record, so you can grant a
  role to somebody who has never signed in. It applies the first time they do.
- **Changes are immediate.** Permissions resolve per request, so revoking one takes effect on that user's
  next click rather than at their next sign-in.

Enforcement is not on the page. Every endpoint is matched to its permission in `SecurityConfig`, and
`ScopeGuard` still resolves the business units and writes the audit entry — so a hidden nav link is a
convenience and typing the URL returns 403. Every change on this page is audited under the `ACCESS` data
type with the actor, the role and the address.

Two roles are provisioned at startup:

- **Full access** — every permission over every business unit, granted to `hr.automation@leadsquared.com`
  and `nalamati.shirin@leadsquared.com`. It cannot be deleted or stripped of "Manage access", so there is
  no way to click yourself out of this screen. Its permission set is rewritten to *all* permissions on
  every start, so "full access" keeps meaning that as permissions are added.
- **Data source manager** — the Administration permission and nothing else, granted to nobody. It opens
  `/data-source` and reads no employee data: verified live, the ingest status, importable tables, API
  sources and approved headcount all answer, while the overview, risk register, exit analysis,
  compensation bands, audit trail, the access page and the assistant all return 403. That separation is
  the point — looking after where the figures come from does not require reading anybody's data. It is an
  ordinary role, so rename, re-scope or delete it; an empty copy returns at the next start, which grants
  nothing because nobody holds it.

---

## Data Source

`/data-source` replaced the admin console. Its BU-to-HRBP mapping and user records moved to the access
page, where a role now decides both what a person opens and which business units they read — so what is
left is where the data comes from, which is what the screen is named after. Three ways in:

**The workbook ingest.** Unchanged, and still the supported path for the seven HR Ops datasets — it knows
their child tables, their attendance reshaping and their derived columns. The page shows the last run, the
reporting date it produced, the rows held and any warnings, and can re-run it.

**A file into a table.** Pick a table, pick a CSV or `.xlsx`, choose append or replace. Headings are
matched to column names past spacing, casing and punctuation — "Employee ID", `employee_id` and
`EMPLOYEEID` all find the same column — and a heading that matches nothing is reported as skipped rather
than guessed at. Columns the file omits keep the database's own default; columns the table requires and the
file lacks are refused up front, naming them. The whole import is one transaction, so a replace that fails
on row 900 leaves the table as it was. A row the database rejects is reported with its line number and the
reason, and the rest still load.

The table list and every column come from `information_schema`, not from a list in the code: a table added
later is importable without a change here, and a name that is not a real column of a real table never
reaches a statement. `app_users`, `custom_roles`, `bu_assignments`, `audit_events` and the narrative and
ingest tables are excluded outright — a spreadsheet is not an appropriate way to grant access or to write
history that never happened.

**A polled API.** Configure a GET endpoint with an interval, a dotted path to the records inside the
response (`data.items`), an optional key field and optional request headers. **Test** calls it and reports
the status, the round trip, how many records were found at that path, the field names and the first record
— and stores nothing, so it is safe to press repeatedly. A background thread then wakes on a tick and runs
whatever is due, measuring the interval from the end of the last run so a slow endpoint cannot queue
overlapping runs. Each run's outcome is stored on the source, so a failure shows on the screen rather than
only in a log.

Records land in **`api_feed_rows`**: source, fetch time, position, the optional key, and the record as the
endpoint returned it. That shape is deliberate — a feed can be configured on a screen instead of needing a
table design and a migration — and it is a landing table, not a destination. Give a feed its own table once
its shape has settled and it is worth querying by column. It is also the table to drop when the sample feed
has served its purpose; nothing else refers to it.

| Setting | Default | What it does |
|---|---|---|
| `dashboard.data-source.polling-enabled` | `true` | Master switch for the background poller |
| `dashboard.data-source.poll-tick-seconds` | `15` | How often it looks for due sources |
| `dashboard.data-source.request-timeout-seconds` | `20` | |
| `dashboard.data-source.max-response-bytes` | `5000000` | Largest response body accepted |
| `dashboard.data-source.max-records-per-run` | `5000` | Records written from one run |
| `dashboard.data-source.allow-private-hosts` | `true` | Whether a configured URL may point at localhost or inside the network |

A configured URL is fetched by the server, so an administrator can point it anywhere the server can reach.
That is inherent to the feature and the reason it sits behind `MANAGE_CONFIG`; the last setting above is
how you turn it off where that is not acceptable. Requests are GET only, follow no redirects, and carry no
credential of the application's own — only headers an administrator typed. Every import and every source
change is audited under the `DATA_SOURCE` type.

---

## Ask Robin — the dashboard assistant

A chat panel at the bottom right of every view, opened from the Robin mark. It answers questions about
the figures in front of the reader: what moved, what it means, how it is derived, and what to look at
next.

It is a data read like any other, so it goes through `ScopeGuard` with the current business unit and
filters attached, writes an `ASK_ASSISTANT` audit event whether granted or denied, and refuses a BU
outside the caller's assignment. The Admin role, which has no employee-data access, does not see the
panel at all.

**What the model is given.** `DashboardBrief` writes out the figures already computed for the
authorised dataset — the metric cards with their month-on-month movement and basis, the risk register
with its factor counts, exit themes and verbatims, PMS and eNPS, leave and attendance and its
anomalies, the calendar, a per-BU breakdown for an org-wide reader, and what this extract cannot
answer. That brief is the model's only source of figures: it has no tools and no query access. For a
role below HRBP the brief contains no name and no employee id anywhere in it, so there is nothing
individual for an answer to repeat — `AssistantBriefPiiTest` asserts that on the same fixture the
calendar PII test uses.

**Conversation memory.** The last **fifty exchanges** travel with each question, so follow-ups like
"and what about the one you mentioned first?" work. The transcript lives in the reader's browser, keyed
by their signed-in address, rather than in a table: a conversation that may name individuals would
otherwise be a second store of identifiable data needing its own retention rule. It is sanitised
server-side before it is sent — roles normalised, empty turns dropped, consecutive same-role turns
merged, and the oldest trimmed by message count and then by size — and the model is told that
conversation text is never an instruction. `ChatHistoryTest` covers each of those.

**Cost.** The brief and the instructions are sent as a cached prefix, so the second and later questions
in a conversation about the same view read it from cache rather than paying for it again — measured at
about 8,100 cached tokens read against 12 fresh input tokens on a follow-up.

**Difference from the narrative.** A narrative may cite only numbers present in its fact sheet and is
rejected if it does not. An answer is allowed to do arithmetic on the briefed figures, because a reader
asking "how much worse is that than last month" wants the subtraction done — the same check would
reject correct answers. The model is instructed to say when a number is derived, and the panel says to
check anything you plan to act on against the cards. Section 9 of
[`docs/ACCESS-CONTROL.md`](docs/ACCESS-CONTROL.md) records this as the assistant's residual risk.

A blank `claude.api-key` disables it: `/api/chat/availability` reports false and the launcher never
appears, rather than offering an input that can only fail.

| Setting | Default | What it does |
|---|---|---|
| `claude.chat-model` | `claude-opus-5` | The assistant's model. The narrative keeps its own `claude.model`. |
| `claude.chat-effort` | `medium` | Reasoning depth: `low` to `max`. Trades answer quality against how long the reader waits — about 6–10s at `medium`. |
| `claude.chat-max-tokens` | `8000` | Answer ceiling. A reply that hits it is flagged as cut off in the panel. |
| `claude.chat-timeout-seconds` | `90` | |
| `claude.chat-history-messages` | `100` | Prior messages carried as context — user and assistant counted together, so 100 is fifty exchanges. |
| `claude.chat-history-chars` | `40000` | Size ceiling on that transcript, applied after the message count. |

---

## Tests

```bash
cd backend && mvn test
```

70 tests, covering the things that would be most damaging to get wrong:

- **`ScopeGuardTest`** — cross-BU isolation. A role scoped to Engineering is denied Sales data; a
  differently cased or unknown BU identifier is denied rather than silently returning nothing; a role
  asking for everything receives only its own BUs; an address with no role reaches nothing; a
  configuration-only role cannot read employee data; a role without individual rights is refused
  individual-grain data; every read is audited.
- **`NarrativeVerificationTest`** — figure traceability. An invented figure is caught, a rounded
  restatement of a real figure is allowed, and formatting differences do not cause false positives.
- **`AccessPolicyTest`** — what an identity resolves to: no role means no access, a role decides outright
  rather than adding to the record's tier, two roles union, an unknown business unit is dropped rather than
  reaching a query, and membership ignores case.
- **`AssistantBriefPiiTest`** — the assistant's brief holds no name and no employee id for a role below
  HRBP, and states the scope and reporting date it was built for.
- **`ChatHistoryTest`** — the conversation sent to the model: fifty exchanges carried, oldest trimmed
  first, the question never dropped, same-role turns merged, roles alternating from a user turn, and
  anything posing as a system turn discarded.
- **`CalendarPiiTest`** — individual calendar events are withheld below HRBP while the per-type counts
  stay true.
- **`SeededRolesTest`** — what startup provisions: the full-access role holds every permission that exists
  rather than a list frozen when it was written, the data-source role holds configuration alone and neither
  employee data nor the audit trail, it starts with no members, and an existing role of either name is left
  alone so an administrator's edit survives a restart.
- **`DataSourceTest`** — the data-source paths that fail quietly: a quoted comma or newline in a CSV stays
  one field, a ragged row is padded, a byte-order mark does not become part of a heading, a heading that is
  not a column matches nothing rather than the nearest thing, a dotted JSON path finds what it should, and
  a source is due on its interval rather than on every tick.
- **`DatasetCacheTest`** — a cached dataset cannot be served across scopes, and a new ingest drops it.
- **`CsvExportServiceTest`** — the filtered CSV export: compensation columns exist only for a caller
  holding `SEE_COMPENSATION`, one row per employee including exited ones (with no stale risk score
  attached to them), every row has the same field count as the header, and a value that looks like a
  spreadsheet formula (`=`, `+`, `-`, `@`) is neutralised rather than written verbatim.

---

## Performance

Measured against MySQL in Docker on the same machine. The figures below are the state after the
17 Aug 2026 changes; the Atlas-era numbers are kept for comparison because they are what the
architecture notes were written against.

| Scope | Now (MySQL, cached) | First read after a restart | Atlas, uncached |
|---|---|---|---|
| Org-wide (5,000 employees) — HR Head, CHRO | 0.06–0.09s | ~11s | 16–80s |
| One BU (~620 employees) — the HRBP path | under 0.1s | ~2s | 1.5–3s |
| PDF deck export | 0.8s | — | ~3s |

A dashboard read assembles one dataset — six reads joined for the scope in question — and every view
on the page then shares it. What made the difference:

- The per-day attendance register is a separate table (`attendance_days`) rather than part of the
  month row, so ~903,000 day marks cannot be pulled into a dashboard read. Loading them exhausted a
  2 GB heap under Mongo.
- Attendance is limited to the months a metric actually reads.
- The six reads are issued concurrently rather than in sequence.
- The heat map builds its eight BU rows concurrently, which halved it.
- **A server-side dataset cache** (`DatasetCache`), keyed by authorised BU list, filter spec and
  ingest generation. This is what takes a view from ~11s to under 0.1s.

> **Open compliance question.** Rule 6 of the access-control mandate reads as forbidding a shared
> cache between sessions holding identifiable employee data, and a cache keyed by BU and filters is
> what section 6 of [`docs/ACCESS-CONTROL.md`](docs/ACCESS-CONTROL.md) says was deliberately not
> built for that reason. The cache cannot widen access — its key includes the authorised BU list, so
> an entry cannot be served to a caller with a different scope — but it is shared state holding
> identifiable data, and that needs HR Ops sign-off, an amendment to Rule 6, or a narrower cache of
> non-identifiable aggregates only (which section 6 already states would be compliant).
# people-insights-dashboard
