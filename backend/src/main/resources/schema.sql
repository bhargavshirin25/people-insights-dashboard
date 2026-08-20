-- Robin Insights Dashboard — MySQL schema.
--
-- Every statement is IF NOT EXISTS, so this runs on every start and is safe to re-run. Column names
-- are the snake_case form of the Java property names, which is what Spring Data JDBC's default
-- naming strategy expects; renaming a column here without renaming the property breaks the mapping.
--
-- Ids that came from Mongo are 24-character ObjectId hex strings or UUIDs, so VARCHAR(64) holds both
-- and the migration preserves them rather than reassigning — an audit trail whose ids changed under
-- it is no longer the same trail.

-- ---------------------------------------------------------------- 1. employee master

CREATE TABLE IF NOT EXISTS employees (
  employee_id            VARCHAR(32)  NOT NULL,
  first_name             VARCHAR(120),
  last_name              VARCHAR(120),
  full_name              VARCHAR(240),
  hrbp_name              VARCHAR(240),
  date_of_joining        DATE,
  email                  VARCHAR(240),
  status                 VARCHAR(32),
  designation            VARCHAR(160),
  grade                  VARCHAR(32),
  vertical               VARCHAR(120),
  department_hierarchy   VARCHAR(320),
  department             VARCHAR(160),
  sub_division           VARCHAR(160),
  cost_center            VARCHAR(160),
  cost_center_id         VARCHAR(64),
  manager_name           VARCHAR(240),
  manager_id             VARCHAR(32),
  l2_manager             VARCHAR(240),
  function_head_name     VARCHAR(240),
  function_head_id       VARCHAR(32),
  bu_head_name           VARCHAR(240),
  bu_head_id             VARCHAR(32),
  employee_type          VARCHAR(64),
  date_of_exit           DATE,
  office_location        VARCHAR(120),
  base_office_location   VARCHAR(120),
  probation_start_date   DATE,
  probation_period_days  INT,
  probation_end_date     DATE,
  confirmation_date      DATE,
  exit_type              VARCHAR(64),
  PRIMARY KEY (employee_id),
  KEY emp_bu_status (vertical, status),
  KEY emp_grade (grade),
  KEY emp_department (department),
  KEY emp_email (email),
  -- Functional index for the IgnoreCase lookup. Spring Data renders "findByEmailIgnoreCase" as
  -- UPPER(email) = UPPER(?), and a function on the column makes the plain index above unusable — the
  -- query falls back to scanning the table. This index matches the expression, so it does not.
  KEY emp_email_upper ((UPPER(email))),
  KEY emp_exit (date_of_exit)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- PMS history: three cycles per employee in the supplied extract. `employee_key` is the list index,
-- which is what preserves cycle order through a round trip.
CREATE TABLE IF NOT EXISTS employee_pms_cycles (
  employee                   VARCHAR(32) NOT NULL,
  employee_key               INT         NOT NULL,
  cycle                      VARCHAR(32),
  rating                     INT,
  appraisal_date             DATE,
  promoted                   TINYINT(1)  NOT NULL DEFAULT 0,
  pre_promotion_designation  VARCHAR(160),
  post_promotion_designation VARCHAR(160),
  promotion_date             DATE,
  PRIMARY KEY (employee, employee_key),
  CONSTRAINT fk_pms_employee FOREIGN KEY (employee) REFERENCES employees (employee_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- 2. compensation

CREATE TABLE IF NOT EXISTS compensation (
  employee_id             VARCHAR(32) NOT NULL,
  full_name               VARCHAR(240),
  grade                   VARCHAR(32),
  vertical                VARCHAR(120),
  designation             VARCHAR(160),
  location                VARCHAR(120),
  employee_type           VARCHAR(64),
  annual_fixed_ctc        DECIMAL(14,2),
  annual_variable_target  DECIMAL(14,2),
  variable_pct_of_ctc     DECIMAL(9,4),
  total_target_ctc        DECIMAL(14,2),
  monthly_ctc             DECIMAL(14,2),
  monthly_basic           DECIMAL(14,2),
  monthly_hra             DECIMAL(14,2),
  special_allowance       DECIMAL(14,2),
  employer_pf             DECIMAL(14,2),
  gratuity_provision      DECIMAL(14,2),
  monthly_gross           DECIMAL(14,2),
  employee_pf_deduction   DECIMAL(14,2),
  professional_tax        DECIMAL(14,2),
  est_monthly_in_hand     DECIMAL(14,2),
  grade_band_min          DECIMAL(14,2),
  grade_band_midpoint     DECIMAL(14,2),
  grade_band_max          DECIMAL(14,2),
  compa_ratio             DECIMAL(9,4),
  band_position           VARCHAR(64),
  last_increment_pct      DECIMAL(9,4),
  last_increment_date     DATE,
  PRIMARY KEY (employee_id),
  KEY comp_bu (vertical),
  KEY comp_grade (grade)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- 3. leave

CREATE TABLE IF NOT EXISTS leave_balances (
  employee_id              VARCHAR(32) NOT NULL,
  full_name                VARCHAR(240),
  grade                    VARCHAR(32),
  vertical                 VARCHAR(120),
  department               VARCHAR(160),
  gender                   VARCHAR(32),
  status                   VARCHAR(32),
  date_of_joining          DATE,
  el_opening_balance       DECIMAL(9,2),
  el_annual_entitlement    DECIMAL(9,2),
  el_total_available       DECIMAL(9,2),
  el_taken                 DECIMAL(9,2),
  el_closing_balance       DECIMAL(9,2),
  el_lapsed                DECIMAL(9,2),
  el_utilisation_pct       DECIMAL(9,4),
  sl_annual_entitlement    DECIMAL(9,2),
  sl_taken                 DECIMAL(9,2),
  sl_balance               DECIMAL(9,2),
  sl_utilisation_pct       DECIMAL(9,4),
  sl_lapsed                DECIMAL(9,2),
  paternity_entitlement    DECIMAL(9,2),
  paternity_taken          DECIMAL(9,2),
  paternity_balance        DECIMAL(9,2),
  maternity_entitlement    DECIMAL(9,2),
  maternity_taken          DECIMAL(9,2),
  maternity_balance        DECIMAL(9,2),
  total_entitlement        DECIMAL(9,2),
  total_taken              DECIMAL(9,2),
  overall_utilisation_pct  DECIMAL(9,4),
  PRIMARY KEY (employee_id),
  KEY bal_bu (vertical),
  KEY bal_grade (grade)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS leave_transactions (
  transaction_id  VARCHAR(64) NOT NULL,
  employee_id     VARCHAR(32),
  employee_name   VARCHAR(240),
  grade           VARCHAR(32),
  vertical        VARCHAR(120),
  leave_type      VARCHAR(64),
  from_date       DATE,
  to_date         DATE,
  days            DECIMAL(6,2),
  applied_on      DATE,
  reason          VARCHAR(512),
  status          VARCHAR(32),
  PRIMARY KEY (transaction_id),
  KEY txn_employee (employee_id),
  KEY txn_bu (vertical),
  KEY txn_type (leave_type),
  KEY txn_from (from_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- 4. attendance

-- One row per employee per month, carrying the counts every attendance metric actually reads.
CREATE TABLE IF NOT EXISTS attendance_months (
  id                       VARCHAR(64) NOT NULL,
  employee_id              VARCHAR(32),
  full_name                VARCHAR(240),
  vertical                 VARCHAR(120),
  department               VARCHAR(160),
  grade                    VARCHAR(32),
  location                 VARCHAR(120),
  year                     INT         NOT NULL,
  month                    INT         NOT NULL,
  year_month_key           VARCHAR(16),
  present_days             INT         NOT NULL DEFAULT 0,
  absent_days              INT         NOT NULL DEFAULT 0,
  lop_days                 INT         NOT NULL DEFAULT 0,
  leave_days               INT         NOT NULL DEFAULT 0,
  half_days                INT         NOT NULL DEFAULT 0,
  week_off_days            INT         NOT NULL DEFAULT 0,
  single_punch_days        INT         NOT NULL DEFAULT 0,
  regularisation_requests  INT         NOT NULL DEFAULT 0,
  working_days             INT         NOT NULL DEFAULT 0,
  attendance_rate_pct      DECIMAL(9,4) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY att_bu_month (vertical, year_month_key),
  KEY att_employee (employee_id),
  KEY att_month (year_month_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The per-day register: ~900,000 rows. Deliberately NOT part of the AttendanceMonth aggregate — no
-- metric reads day-level detail, and loading it with every dashboard read exhausted the heap under
-- Mongo for exactly the same reason. It is migrated and indexed so a future day-level drill-down can
-- query one employee-month directly.
CREATE TABLE IF NOT EXISTS attendance_days (
  attendance_month_id  VARCHAR(64) NOT NULL,
  day                  INT         NOT NULL,
  day_date             VARCHAR(32),
  code                 VARCHAR(16),
  category             VARCHAR(32),
  PRIMARY KEY (attendance_month_id, day),
  CONSTRAINT fk_day_month FOREIGN KEY (attendance_month_id) REFERENCES attendance_months (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- 5. eNPS

CREATE TABLE IF NOT EXISTS enps_responses (
  id            VARCHAR(64) NOT NULL,
  employee_id   VARCHAR(32),
  full_name     VARCHAR(240),
  vertical      VARCHAR(120),
  grade         VARCHAR(32),
  nps_score     INT,
  nps_category  VARCHAR(32),
  survey_date   DATE,
  cycle         VARCHAR(32),
  comments      TEXT,
  PRIMARY KEY (id),
  KEY enps_bu_cycle (vertical, cycle),
  KEY enps_employee (employee_id),
  KEY enps_category (nps_category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- 6. exits

CREATE TABLE IF NOT EXISTS exit_records (
  employee_id          VARCHAR(32) NOT NULL,
  grade                VARCHAR(32),
  vertical             VARCHAR(120),
  department           VARCHAR(160),
  designation          VARCHAR(160),
  exit_type            VARCHAR(64),
  date_of_joining      DATE,
  date_of_exit         DATE,
  tenure_months        DECIMAL(9,2),
  tenure_band          VARCHAR(64),
  primary_exit_reason  VARCHAR(320),
  overall_score        INT,
  overall_sentiment    VARCHAR(32),
  verbatim             TEXT,
  PRIMARY KEY (employee_id),
  KEY exit_bu_date (vertical, date_of_exit),
  KEY exit_type_idx (exit_type),
  KEY exit_dept (department),
  KEY exit_band (tenure_band)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS exit_record_themes (
  exit_record      VARCHAR(32) NOT NULL,
  exit_record_key  INT         NOT NULL,
  name             VARCHAR(160),
  score            INT,
  sentiment        VARCHAR(32),
  PRIMARY KEY (exit_record, exit_record_key),
  CONSTRAINT fk_theme_exit FOREIGN KEY (exit_record) REFERENCES exit_records (employee_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- 7. users, roles, scope

CREATE TABLE IF NOT EXISTS app_users (
  id                  VARCHAR(64)  NOT NULL,
  email               VARCHAR(240) NOT NULL,
  display_name        VARCHAR(240),
  role                VARCHAR(32),
  -- JSON array of business unit names. A user's scope is read on every request, so it stays on the
  -- row rather than costing a join.
  assigned_bus_json   TEXT,
  dev_password_hash   VARCHAR(120),
  enabled             TINYINT(1)   NOT NULL DEFAULT 1,
  created_at          DATETIME(6),
  last_login_at       DATETIME(6),
  saved_filter_json   TEXT,
  PRIMARY KEY (id),
  UNIQUE KEY user_email (email),
  -- Every authenticated request resolves the signed-in user by email through an IgnoreCase lookup,
  -- which renders as UPPER(email) = UPPER(?) and cannot use the unique key above.
  KEY user_email_upper ((UPPER(email)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bu_assignments (
  id                   VARCHAR(64)  NOT NULL,
  hrbp_email           VARCHAR(240) NOT NULL,
  hrbp_name            VARCHAR(240),
  business_units_json  TEXT,
  updated_by           VARCHAR(240),
  updated_at           DATETIME(6),
  PRIMARY KEY (id),
  UNIQUE KEY assignment_email (hrbp_email),
  KEY assignment_email_upper ((UPPER(hrbp_email)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS custom_roles (
  id                   VARCHAR(64)  NOT NULL,
  name                 VARCHAR(120) NOT NULL,
  description          VARCHAR(500),
  -- JSON arrays, for the same reason app_users.assigned_bus_json is one: a role's permissions, scope
  -- and membership are read as a whole on every sign-in and never queried across.
  permissions_json     TEXT,
  business_units_json  TEXT,
  all_business_units   TINYINT(1)   NOT NULL DEFAULT 0,
  member_emails_json   TEXT,
  -- Set on the seeded full-access role only. It cannot be deleted, and it cannot have MANAGE_ACCESS
  -- removed, so revoking the last administrator can never leave the access page unreachable.
  system_role          TINYINT(1)   NOT NULL DEFAULT 0,
  created_by           VARCHAR(240),
  created_at           DATETIME(6),
  updated_by           VARCHAR(240),
  updated_at           DATETIME(6),
  PRIMARY KEY (id),
  UNIQUE KEY role_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- 7b. data sources

CREATE TABLE IF NOT EXISTS api_sources (
  id                   VARCHAR(64)  NOT NULL,
  name                 VARCHAR(120) NOT NULL,
  -- GET only. The service refuses anything else, and refuses a scheme other than http or https.
  url                  VARCHAR(2000) NOT NULL,
  -- JSON object of request headers, for an API key or an Accept override.
  headers_json         TEXT,
  -- Dotted path to the array inside the response, empty for the root. "data.items" reads
  -- {"data":{"items":[…]}}.
  json_path            VARCHAR(240),
  -- Field inside each record to keep as a readable key. Optional; the raw record is stored regardless.
  key_field            VARCHAR(120),
  interval_seconds     INT          NOT NULL DEFAULT 300,
  enabled              TINYINT(1)   NOT NULL DEFAULT 1,
  -- Snapshot semantics: clear this source's previous rows before writing the new ones. Off appends.
  replace_each_run     TINYINT(1)   NOT NULL DEFAULT 1,
  last_run_at          DATETIME(6),
  last_status          VARCHAR(32),
  last_message         VARCHAR(1000),
  last_row_count       INT,
  created_by           VARCHAR(240),
  created_at           DATETIME(6),
  updated_by           VARCHAR(240),
  updated_at           DATETIME(6),
  PRIMARY KEY (id),
  UNIQUE KEY api_source_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The landing table every configured API writes into.
--
-- Deliberately shaped for any JSON rather than for one API: a record arrives as the object the endpoint
-- returned, with its position, an optional readable key, and when it was fetched. That is what lets a
-- source be configured on a screen instead of requiring a table design and a migration per feed. Give a
-- feed its own table once its shape has settled and it is worth querying by column.
CREATE TABLE IF NOT EXISTS api_feed_rows (
  id                   VARCHAR(64)  NOT NULL,
  source_id            VARCHAR(64)  NOT NULL,
  source_name          VARCHAR(120),
  fetched_at           DATETIME(6)  NOT NULL,
  record_index         INT          NOT NULL,
  record_key           VARCHAR(240),
  payload_json         LONGTEXT,
  PRIMARY KEY (id),
  KEY feed_source (source_id, fetched_at),
  KEY feed_key (record_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- 8. audit trail

CREATE TABLE IF NOT EXISTS audit_events (
  id             VARCHAR(64) NOT NULL,
  at             DATETIME(6) NOT NULL,
  user_email     VARCHAR(240),
  role           VARCHAR(32),
  business_unit  VARCHAR(120),
  data_type      VARCHAR(64),
  action         VARCHAR(64),
  outcome        VARCHAR(32),
  detail         VARCHAR(1024),
  request_path   VARCHAR(512),
  source_ip      VARCHAR(64),
  PRIMARY KEY (id),
  KEY audit_at (at),
  KEY audit_user_outcome_at (user_email, outcome, at),
  KEY audit_bu (business_unit),
  KEY audit_data_type (data_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- 9. actions, narratives, config

CREATE TABLE IF NOT EXISTS retention_actions (
  id               VARCHAR(64) NOT NULL,
  employee_id      VARCHAR(32),
  vertical         VARCHAR(120),
  action_type      VARCHAR(64),
  action_date      DATE,
  notes            TEXT,
  logged_by_email  VARCHAR(240),
  logged_by_name   VARCHAR(240),
  logged_at        DATETIME(6),
  PRIMARY KEY (id),
  KEY action_employee (employee_id),
  KEY action_bu_logged (vertical, logged_at),
  KEY action_logged_by (logged_by_email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS narratives (
  id                  VARCHAR(64) NOT NULL,
  business_unit       VARCHAR(120),
  filter_key          VARCHAR(512),
  facts_hash          VARCHAR(120),
  text                TEXT,
  model               VARCHAR(120),
  generated_at        DATETIME(6),
  generated_for_user  VARCHAR(240),
  edited_by           VARCHAR(240),
  edited_by_name      VARCHAR(240),
  edited_at           DATETIME(6),
  fallback            TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY narrative_lookup (business_unit, filter_key, generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS narrative_anomalies (
  narrative_doc      VARCHAR(64) NOT NULL,
  narrative_doc_key  INT         NOT NULL,
  metric             VARCHAR(120),
  severity           VARCHAR(32),
  description        VARCHAR(1024),
  PRIMARY KEY (narrative_doc, narrative_doc_key),
  CONSTRAINT fk_anomaly_narrative FOREIGN KEY (narrative_doc) REFERENCES narratives (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS narrative_cited_figures (
  narrative_doc      VARCHAR(64) NOT NULL,
  narrative_doc_key  INT         NOT NULL,
  label              VARCHAR(320),
  value              VARCHAR(320),
  PRIMARY KEY (narrative_doc, narrative_doc_key),
  CONSTRAINT fk_figure_narrative FOREIGN KEY (narrative_doc) REFERENCES narratives (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS open_positions (
  id                 VARCHAR(64) NOT NULL,
  requisition_id     VARCHAR(64),
  vertical           VARCHAR(120),
  department         VARCHAR(160),
  designation        VARCHAR(160),
  grade              VARCHAR(32),
  location           VARCHAR(120),
  approved_count     INT NOT NULL DEFAULT 0,
  filled_count       INT NOT NULL DEFAULT 0,
  approved_on        DATE,
  target_close_date  DATE,
  status             VARCHAR(32),
  updated_by         VARCHAR(240),
  updated_at         DATETIME(6),
  PRIMARY KEY (id),
  KEY position_bu_status (vertical, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ingest_runs (
  id                 VARCHAR(64) NOT NULL,
  started_at         DATETIME(6),
  finished_at        DATETIME(6),
  source_directory   VARCHAR(512),
  -- JSON object of collection -> rows written, and a JSON array of warnings. Both are read only as
  -- a whole, by the admin data-status panel.
  counts_json        TEXT,
  warnings_json      TEXT,
  data_as_of_date    VARCHAR(16),
  status             VARCHAR(32),
  PRIMARY KEY (id),
  KEY ingest_status_finished (status, finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- 10. audit retention
--
-- Mongo enforced the 90-day audit retention with a TTL index. The equivalent here is a scheduled
-- event, so retention stays a property of the database rather than a cleanup job someone has to
-- remember to run. Requires the container to run with --event-scheduler=ON.
--
-- The window is also a floor, not just a ceiling: the access rules require 90 days be kept, so the
-- interval below must never be shortened without changing dashboard.audit-retention-days too.

CREATE EVENT IF NOT EXISTS audit_events_retention
  ON SCHEDULE EVERY 1 DAY
  DO DELETE FROM audit_events WHERE at < NOW() - INTERVAL 90 DAY;
