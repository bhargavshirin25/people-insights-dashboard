# MySQL — container, schema, migration

The data layer is MySQL 8.4 in Docker on the local machine. It was MongoDB Atlas until 17 Aug 2026.

**The Atlas `people_insights` database was dropped on 18 Aug 2026, after the migration was verified.**
MySQL is now the only live copy. A `mongodump` taken immediately before the drop is at
`~/Downloads/mongo-people-insights-backup-2026-08-18/` (6.6 MB gzipped, all 14 collections with their
index metadata) — that archive and the original HR Ops workbooks are the only remaining fallbacks, so
neither should be deleted casually. The other databases on that cluster (`hr_cockpit`, `hrbp_cockpit`,
`hrbp_live`, `dpdbconnector`, `esscontroller`, `sample_mflix`) belong to other work and were untouched.

## The container

```bash
docker run -d --name people-insights-mysql \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=<MYSQL-PASSWORD-REMOVED> \
  -e MYSQL_DATABASE=people_insights \
  -v people-insights-mysql-data:/var/lib/mysql \
  --restart unless-stopped \
  mysql:8.4 \
  --event-scheduler=ON \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_0900_ai_ci \
  --max-allowed-packet=67108864
```

`--event-scheduler=ON` is not optional: audit retention is enforced by a scheduled event, and with the
scheduler off the trail would grow without bound and the 90-day rule would be silently unenforced.

Data lives in the named volume `people-insights-mysql-data`, so `docker rm` on the container does not
destroy it. `docker volume rm people-insights-mysql-data` does.

Connection settings are in `application.yml` under `spring.datasource`, overridable with `MYSQL_URL`,
`MYSQL_USER` and `MYSQL_PASSWORD`. The password above is a local development credential and should not
be reused anywhere the machine is not the only thing that can reach the port.

## The schema

`backend/src/main/resources/schema.sql`, applied on every start (`spring.sql.init.mode: always`).
Every statement is `IF NOT EXISTS`, so a start never alters an existing table — which also means a
column change needs a migration of its own, not an edit to that file.

19 tables. Nine hold the HR Ops datasets and their child rows; the rest hold users, scope
assignments, the audit trail, retention actions, narratives, requisitions and ingest history.

Column names are the snake_case form of the Java property names, because that is what Spring Data
JDBC's default naming strategy looks for. Two exceptions carry an explicit `@Column`:

- `attendance_months.year_month_key` — `YEAR_MONTH` is a reserved word in MySQL, so the obvious name
  would need quoting that the generated SQL does not add.
- `app_users.assigned_bus_json`, `bu_assignments.business_units_json`, `ingest_runs.counts_json`,
  `ingest_runs.warnings_json` — short collections held as JSON text rather than as child tables. They
  are read only as a whole, so a table each would add a join and no query.

### What is not in the aggregates

`attendance_days` holds ~903,000 day marks and is deliberately **not** mapped as a child collection of
`attendance_months`. Spring Data JDBC loads an aggregate whole, so mapping it would drag the entire
register into every dashboard read — the same mistake that exhausted the heap under Mongo. Nothing in
the dashboard reads day-level detail; the counts on the month row are what the metrics use. A future
drill-down should query one employee-month from this table directly.

## Indexes — what is indexed, and what indexing cannot fix

Measured with `performance_schema.events_statements_summary_by_digest` against the real query stream,
not from reading the code.

**Every statement the application issues uses an index, or is correctly deciding not to.**

| Query | Plan |
|---|---|
| `employees` for one BU | `ref` on `emp_bu_status`, 612 rows |
| `employees` org-wide (8 BUs) | full scan — see below |
| `attendance_months` by BU + months | `range` on `att_bu_month`, 3,669 rows, covering |
| child rows (`employee_pms_cycles`, `exit_record_themes`) | `ref` on the primary key, 3 rows |
| audit by user + outcome + time | `audit_user_outcome_at` |
| audit newest-500 and since-instant | `audit_at` |

The org-wide full scan is the optimizer being right, not a missing index: the predicate
`vertical IN (all eight BUs)` matches every row, so scanning beats an index lookup per row. It costs
0.02–0.10s per table, which is not where the time goes.

### The filter fields are not queried

Grade, location, tenure and period — the dashboard's filter bar — are **not** in any `WHERE` clause.
`DatasetLoader` reads the whole authorised scope and `FilterSpec.matches()` applies the filters in Java
on the loaded objects. Indexing `grade` or `office_location` therefore changes nothing about read
speed today. `emp_grade`, `comp_grade` and `bal_grade` exist for ad-hoc queries and for the day the
filtering moves into SQL; they are not on the dashboard's path.

The same is true of the four `leave_transactions` indexes: the dashboard never reads that table
(unplanned absence is measured from the attendance register), so they cost a little ingest write time
and return nothing today.

### Functional indexes on the IgnoreCase lookups

Spring Data renders `findByEmailIgnoreCase` as `WHERE UPPER(email) = UPPER(?)`. A function on the
column makes an ordinary index unusable, so those three lookups were scanning their tables — including
`app_users`, which is read on **every authenticated request**, and `employees`, which has 5,000 rows.
`emp_email_upper`, `user_email_upper` and `assignment_email_upper` index the expression itself, and all
three plans went from `ALL` to `ref` on one row.

Note for anyone changing this file: MySQL has no `CREATE INDEX IF NOT EXISTS`, so these are declared
inside their `CREATE TABLE` statements for fresh environments and were applied to the existing
database with a one-off `ALTER TABLE ... ADD INDEX`. A schema change to a live database still needs its
own migration; this file only guarantees a new one comes up correct.

### What actually costs the time: N+1 child loads

One org-wide read plus one single-BU read issued **7,274 queries**, of which 7,274 minus a dozen were
per-parent child loads: 5,612 against `employee_pms_cycles` and 1,662 against `exit_record_themes`.
Spring Data JDBC loads an aggregate's collections with one query per parent row, so a 5,000-employee
read means 5,000 extra round trips.

Server-side time for all of them is only 1.3s. The rest of the ~12s cold read is round-trip overhead at
roughly 1.6ms each. That is why a single-BU read is 0.77s and an org-wide one is 12s — the difference is
the number of round trips, not the volume of data.

No index can fix this. The fix is to load each child table once per request with
`WHERE parent_id IN (...)` and attach the rows in Java, turning ~6,500 queries into two. Expected
effect on a cold org-wide read: ~12s to ~1s. It has not been done, because it means taking the child
collections out of the aggregates and populating them in `DatasetLoader` instead.

## Audit retention

```sql
CREATE EVENT audit_events_retention
  ON SCHEDULE EVERY 1 DAY
  DO DELETE FROM audit_events WHERE at < NOW() - INTERVAL 90 DAY;
```

This replaces the Mongo TTL index. Retention stays a property of the database rather than a cleanup
job someone has to remember to run. Check it is alive with:

```sql
SELECT event_name, status, last_executed FROM information_schema.events;
```

`STATUS` must be `ENABLED`. The 90 days is a floor the access rules require, not a target — shortening
it changes what HR Ops signed off, and `dashboard.audit-retention-days` has to move with it.

## The Mongo importer

`migrate/MongoToMysqlMigration` is still in the codebase, but **its source no longer exists** — the
Atlas database was dropped on 18 Aug 2026. Running it now would empty each MySQL table and refill it
with nothing, and the count check at the end would pass because zero equals zero. Restore the dump to a
Mongo instance first if it is ever needed again:

```bash
docker run --rm -v ~/Downloads:/backup mongo:8 mongorestore \
  --uri="mongodb://<target-host>" --gzip \
  /backup/mongo-people-insights-backup-2026-08-18
```

Deleting the importer, the `mongodb-driver-sync` dependency and the `migration.mongodb.*` properties is
now a safe cleanup whenever the cutover is considered final.

The command it was run with, for the record:

```bash
cd backend
JAVA_HOME=/path/to/jdk-21 mvn spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--migrate-from-mongo" \
  -Dspring-boot.run.jvmArguments="-Xmx3g"
```

It empties each table immediately before refilling it, copies ids across unchanged, and finishes by
reading the row counts back out of MySQL and comparing them to what it inserted. A mismatch fails the
run rather than reporting success. It reads Mongo and never writes to it.

Row counts from the 17 Aug 2026 cutover:

| Table | Rows |
|---|---|
| `employees` | 5,000 |
| `employee_pms_cycles` | 9,904 |
| `compensation` | 5,000 |
| `leave_balances` | 5,000 |
| `leave_transactions` | 32,689 |
| `attendance_months` | 29,960 |
| `attendance_days` | 903,030 |
| `enps_responses` | 5,000 |
| `exit_records` | 1,476 |
| `exit_record_themes` | 2,887 |
| `app_users` | 14 |
| `bu_assignments` | 9 |
| `audit_events` | 843 |
| `retention_actions` | 1 |
| `narratives` | 37 |
| `narrative_anomalies` | 170 |
| `narrative_cited_figures` | 333 |
| `open_positions` | 0 |
| `ingest_runs` | 2 |
| **Total** | **1,001,455** |

Numeric fidelity was checked rather than assumed: no value in any `DECIMAL` column uses even its
second decimal place, so nothing was rounded on the way in. Money is `DECIMAL(14,2)` and rates are
`DECIMAL(9,4)`. If a future extract carries more precision than the current one, those widths are the
place to look first.

## Insert against update

MySQL cannot generate a `VARCHAR` primary key, so ids are assigned in the application rather than by
the database — which removes the null-id signal Spring Data used to tell a new object from a loaded
one. Two mechanisms replace it:

- `AppUser`, `BuAssignment` and `NarrativeDoc` implement `Persistable` and are flipped to "loaded" by
  the `AfterConvertCallback`s in `JdbcPersistenceConfig`. `save()` therefore inserts a new one and
  updates a loaded one, as before.
- The immutable records have no such flag, and none of them is ever updated. They are written through
  `Store.insert(...)`, which states the intent instead of relying on a convention, and fails loudly on
  a duplicate id rather than quietly updating nothing.
