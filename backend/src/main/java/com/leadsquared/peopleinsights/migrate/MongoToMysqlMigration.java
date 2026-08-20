package com.leadsquared.peopleinsights.migrate;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-way copy of the Atlas database into MySQL, run with {@code --migrate-from-mongo}.
 *
 * <p>Reads raw BSON documents rather than going through a mapping layer, and writes with explicit SQL.
 * That is deliberate: a migration must be able to read documents the current code no longer models —
 * a field renamed since ingest, a stray null, an extra key — and report on them instead of failing to
 * map and losing the row. Anything it cannot place is counted and logged rather than skipped silently.
 *
 * <p>It never writes to Mongo. Rerunning it is safe: each table is emptied immediately before it is
 * refilled, inside one transaction per table, so a failure part-way leaves the previous contents
 * rather than half of the new ones.
 *
 * <p>Ids are carried across unchanged. An audit trail whose identifiers were reassigned under it would
 * no longer be the same trail, and the retention actions that reference employees by id have to keep
 * resolving.
 */
@Component
@Order(1)
public class MongoToMysqlMigration implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(MongoToMysqlMigration.class);

  /** Rows per batch. Large enough to be fast, small enough to keep max_allowed_packet comfortable. */
  private static final int BATCH = 2000;

  private final JdbcTemplate jdbc;
  private final String mongoUri;
  private final String mongoDatabase;

  public MongoToMysqlMigration(
      JdbcTemplate jdbc,
      @Value("${migration.mongodb.uri:}") String mongoUri,
      @Value("${migration.mongodb.database:people_insights}") String mongoDatabase) {
    this.jdbc = jdbc;
    this.mongoUri = mongoUri;
    this.mongoDatabase = mongoDatabase;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!args.containsOption("migrate-from-mongo")) {
      return;
    }
    if (mongoUri == null || mongoUri.isBlank()) {
      throw new IllegalStateException(
          "migration.mongodb.uri is not set; nothing to migrate from.");
    }

    Instant started = Instant.now();
    Map<String, Integer> written = new LinkedHashMap<>();

    try (MongoClient client = MongoClients.create(mongoUri)) {
      MongoDatabase db = client.getDatabase(mongoDatabase);
      log.info("Migrating {} -> MySQL", mongoDatabase);

      written.put("employees", employees(db));
      written.put("employee_pms_cycles", pmsCycles(db));
      written.put("compensation", compensation(db));
      written.put("leave_balances", leaveBalances(db));
      written.put("leave_transactions", leaveTransactions(db));
      written.put("attendance_months", attendanceMonths(db));
      written.put("attendance_days", attendanceDays(db));
      written.put("enps_responses", enps(db));
      written.put("exit_records", exitRecords(db));
      written.put("exit_record_themes", exitThemes(db));
      written.put("app_users", appUsers(db));
      written.put("bu_assignments", buAssignments(db));
      written.put("audit_events", auditEvents(db));
      written.put("retention_actions", retentionActions(db));
      written.put("narratives", narratives(db));
      written.put("narrative_anomalies", narrativeChildren(db, "anomalies"));
      written.put("narrative_cited_figures", narrativeChildren(db, "citedFigures"));
      written.put("open_positions", openPositions(db));
      written.put("ingest_runs", ingestRuns(db));
    }

    log.info(
        "Migration finished in {}s",
        (Instant.now().toEpochMilli() - started.toEpochMilli()) / 1000);
    verify(written);
  }

  // ---------------------------------------------------------------- the seven datasets

  private int employees(MongoDatabase db) {
    return copy(
        db,
        "employees",
        "employees",
        """
        INSERT INTO employees (employee_id, first_name, last_name, full_name, hrbp_name,
          date_of_joining, email, status, designation, grade, vertical, department_hierarchy,
          department, sub_division, cost_center, cost_center_id, manager_name, manager_id,
          l2_manager, function_head_name, function_head_id, bu_head_name, bu_head_id,
          employee_type, date_of_exit, office_location, base_office_location, probation_start_date,
          probation_period_days, probation_end_date, confirmation_date, exit_type)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "firstName"), str(d, "lastName"), str(d, "fullName"),
              str(d, "hrbpName"), date(d, "dateOfJoining"), str(d, "email"), str(d, "status"),
              str(d, "designation"), str(d, "grade"), str(d, "vertical"),
              str(d, "departmentHierarchy"), str(d, "department"), str(d, "subDivision"),
              str(d, "costCenter"), str(d, "costCenterId"), str(d, "managerName"),
              str(d, "managerId"), str(d, "l2Manager"), str(d, "functionHeadName"),
              str(d, "functionHeadId"), str(d, "buHeadName"), str(d, "buHeadId"),
              str(d, "employeeType"), date(d, "dateOfExit"), str(d, "officeLocation"),
              str(d, "baseOfficeLocation"), date(d, "probationStartDate"),
              integer(d, "probationPeriodDays"), date(d, "probationEndDate"),
              date(d, "confirmationDate"), str(d, "exitType")
            });
  }

  private int pmsCycles(MongoDatabase db) {
    return copyChildren(
        db,
        "employees",
        "employee_pms_cycles",
        "pms",
        """
        INSERT INTO employee_pms_cycles (employee, employee_key, cycle, rating, appraisal_date,
          promoted, pre_promotion_designation, post_promotion_designation, promotion_date)
        VALUES (?,?,?,?,?,?,?,?,?)
        """,
        (parent, child, index) ->
            new Object[] {
              str(parent, "_id"), index, str(child, "cycle"), integer(child, "rating"),
              date(child, "appraisalDate"), bool(child, "promoted"),
              str(child, "prePromotionDesignation"), str(child, "postPromotionDesignation"),
              date(child, "promotionDate")
            });
  }

  private int compensation(MongoDatabase db) {
    return copy(
        db,
        "compensation",
        "compensation",
        """
        INSERT INTO compensation (employee_id, full_name, grade, vertical, designation, location,
          employee_type, annual_fixed_ctc, annual_variable_target, variable_pct_of_ctc,
          total_target_ctc, monthly_ctc, monthly_basic, monthly_hra, special_allowance, employer_pf,
          gratuity_provision, monthly_gross, employee_pf_deduction, professional_tax,
          est_monthly_in_hand, grade_band_min, grade_band_midpoint, grade_band_max, compa_ratio,
          band_position, last_increment_pct, last_increment_date)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "fullName"), str(d, "grade"), str(d, "vertical"),
              str(d, "designation"), str(d, "location"), str(d, "employeeType"),
              dbl(d, "annualFixedCtc"), dbl(d, "annualVariableTarget"), dbl(d, "variablePctOfCtc"),
              dbl(d, "totalTargetCtc"), dbl(d, "monthlyCtc"), dbl(d, "monthlyBasic"),
              dbl(d, "monthlyHra"), dbl(d, "specialAllowance"), dbl(d, "employerPf"),
              dbl(d, "gratuityProvision"), dbl(d, "monthlyGross"), dbl(d, "employeePfDeduction"),
              dbl(d, "professionalTax"), dbl(d, "estMonthlyInHand"), dbl(d, "gradeBandMin"),
              dbl(d, "gradeBandMidpoint"), dbl(d, "gradeBandMax"), dbl(d, "compaRatio"),
              str(d, "bandPosition"), dbl(d, "lastIncrementPct"), date(d, "lastIncrementDate")
            });
  }

  private int leaveBalances(MongoDatabase db) {
    return copy(
        db,
        "leave_balances",
        "leave_balances",
        """
        INSERT INTO leave_balances (employee_id, full_name, grade, vertical, department, gender,
          status, date_of_joining, el_opening_balance, el_annual_entitlement, el_total_available,
          el_taken, el_closing_balance, el_lapsed, el_utilisation_pct, sl_annual_entitlement,
          sl_taken, sl_balance, sl_utilisation_pct, sl_lapsed, paternity_entitlement,
          paternity_taken, paternity_balance, maternity_entitlement, maternity_taken,
          maternity_balance, total_entitlement, total_taken, overall_utilisation_pct)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "fullName"), str(d, "grade"), str(d, "vertical"),
              str(d, "department"), str(d, "gender"), str(d, "status"), date(d, "dateOfJoining"),
              dbl(d, "elOpeningBalance"), dbl(d, "elAnnualEntitlement"), dbl(d, "elTotalAvailable"),
              dbl(d, "elTaken"), dbl(d, "elClosingBalance"), dbl(d, "elLapsed"),
              dbl(d, "elUtilisationPct"), dbl(d, "slAnnualEntitlement"), dbl(d, "slTaken"),
              dbl(d, "slBalance"), dbl(d, "slUtilisationPct"), dbl(d, "slLapsed"),
              dbl(d, "paternityEntitlement"), dbl(d, "paternityTaken"), dbl(d, "paternityBalance"),
              dbl(d, "maternityEntitlement"), dbl(d, "maternityTaken"), dbl(d, "maternityBalance"),
              dbl(d, "totalEntitlement"), dbl(d, "totalTaken"), dbl(d, "overallUtilisationPct")
            });
  }

  private int leaveTransactions(MongoDatabase db) {
    return copy(
        db,
        "leave_transactions",
        "leave_transactions",
        """
        INSERT INTO leave_transactions (transaction_id, employee_id, employee_name, grade, vertical,
          leave_type, from_date, to_date, days, applied_on, reason, status)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "employeeId"), str(d, "employeeName"), str(d, "grade"),
              str(d, "vertical"), str(d, "leaveType"), date(d, "fromDate"), date(d, "toDate"),
              dbl(d, "days"), date(d, "appliedOn"), str(d, "reason"), str(d, "status")
            });
  }

  private int attendanceMonths(MongoDatabase db) {
    return copy(
        db,
        "attendance_months",
        "attendance_months",
        """
        INSERT INTO attendance_months (id, employee_id, full_name, vertical, department, grade,
          location, year, month, year_month_key, present_days, absent_days, lop_days, leave_days,
          half_days, week_off_days, single_punch_days, regularisation_requests, working_days,
          attendance_rate_pct)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "employeeId"), str(d, "fullName"), str(d, "vertical"),
              str(d, "department"), str(d, "grade"), str(d, "location"), intOrZero(d, "year"),
              intOrZero(d, "month"), str(d, "yearMonth"), intOrZero(d, "presentDays"),
              intOrZero(d, "absentDays"), intOrZero(d, "lopDays"), intOrZero(d, "leaveDays"),
              intOrZero(d, "halfDays"), intOrZero(d, "weekOffDays"), intOrZero(d, "singlePunchDays"),
              intOrZero(d, "regularisationRequests"), intOrZero(d, "workingDays"),
              dblOrZero(d, "attendanceRatePct")
            });
  }

  /** The ~900,000 day marks, lifted out of the month documents into their own table. */
  private int attendanceDays(MongoDatabase db) {
    return copyChildren(
        db,
        "attendance_months",
        "attendance_days",
        "days",
        """
        INSERT INTO attendance_days (attendance_month_id, day, day_date, code, category)
        VALUES (?,?,?,?,?)
        """,
        (parent, child, index) ->
            new Object[] {
              str(parent, "_id"), intOrZero(child, "day"), str(child, "date"), str(child, "code"),
              str(child, "category")
            });
  }

  private int enps(MongoDatabase db) {
    return copy(
        db,
        "enps_responses",
        "enps_responses",
        """
        INSERT INTO enps_responses (id, employee_id, full_name, vertical, grade, nps_score,
          nps_category, survey_date, cycle, comments)
        VALUES (?,?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "employeeId"), str(d, "fullName"), str(d, "vertical"),
              str(d, "grade"), integer(d, "npsScore"), str(d, "npsCategory"), date(d, "surveyDate"),
              str(d, "cycle"), str(d, "comments")
            });
  }

  private int exitRecords(MongoDatabase db) {
    return copy(
        db,
        "exit_records",
        "exit_records",
        """
        INSERT INTO exit_records (employee_id, grade, vertical, department, designation, exit_type,
          date_of_joining, date_of_exit, tenure_months, tenure_band, primary_exit_reason,
          overall_score, overall_sentiment, verbatim)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "grade"), str(d, "vertical"), str(d, "department"),
              str(d, "designation"), str(d, "exitType"), date(d, "dateOfJoining"),
              date(d, "dateOfExit"), dbl(d, "tenureMonths"), str(d, "tenureBand"),
              str(d, "primaryExitReason"), integer(d, "overallScore"), str(d, "overallSentiment"),
              str(d, "verbatim")
            });
  }

  private int exitThemes(MongoDatabase db) {
    return copyChildren(
        db,
        "exit_records",
        "exit_record_themes",
        "themes",
        """
        INSERT INTO exit_record_themes (exit_record, exit_record_key, name, score, sentiment)
        VALUES (?,?,?,?,?)
        """,
        (parent, child, index) ->
            new Object[] {
              str(parent, "_id"), index, str(child, "name"), integer(child, "score"),
              str(child, "sentiment")
            });
  }

  // ---------------------------------------------------------------- users, audit, operational state

  private int appUsers(MongoDatabase db) {
    return copy(
        db,
        "app_users",
        "app_users",
        """
        INSERT INTO app_users (id, email, display_name, role, assigned_bus_json, dev_password_hash,
          enabled, created_at, last_login_at, saved_filter_json)
        VALUES (?,?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "email"), str(d, "displayName"), str(d, "role"),
              jsonArray(d, "assignedBus"), str(d, "devPasswordHash"), boolOrTrue(d, "enabled"),
              instant(d, "createdAt"), instant(d, "lastLoginAt"), str(d, "savedFilterJson")
            });
  }

  private int buAssignments(MongoDatabase db) {
    return copy(
        db,
        "bu_assignments",
        "bu_assignments",
        """
        INSERT INTO bu_assignments (id, hrbp_email, hrbp_name, business_units_json, updated_by,
          updated_at)
        VALUES (?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "hrbpEmail"), str(d, "hrbpName"),
              jsonArray(d, "businessUnits"), str(d, "updatedBy"), instant(d, "updatedAt")
            });
  }

  private int auditEvents(MongoDatabase db) {
    return copy(
        db,
        "audit_events",
        "audit_events",
        """
        INSERT INTO audit_events (id, at, user_email, role, business_unit, data_type, action,
          outcome, detail, request_path, source_ip)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), instantOrNow(d, "at"), str(d, "userEmail"), str(d, "role"),
              str(d, "businessUnit"), str(d, "dataType"), str(d, "action"), str(d, "outcome"),
              truncate(str(d, "detail"), 1024), truncate(str(d, "requestPath"), 512),
              str(d, "sourceIp")
            });
  }

  private int retentionActions(MongoDatabase db) {
    return copy(
        db,
        "retention_actions",
        "retention_actions",
        """
        INSERT INTO retention_actions (id, employee_id, vertical, action_type, action_date, notes,
          logged_by_email, logged_by_name, logged_at)
        VALUES (?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "employeeId"), str(d, "vertical"), str(d, "actionType"),
              date(d, "actionDate"), str(d, "notes"), str(d, "loggedByEmail"),
              str(d, "loggedByName"), instant(d, "loggedAt")
            });
  }

  private int narratives(MongoDatabase db) {
    return copy(
        db,
        "narratives",
        "narratives",
        """
        INSERT INTO narratives (id, business_unit, filter_key, facts_hash, text, model, generated_at,
          generated_for_user, edited_by, edited_by_name, edited_at, fallback)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "businessUnit"), truncate(str(d, "filterKey"), 512),
              str(d, "factsHash"), str(d, "text"), str(d, "model"), instant(d, "generatedAt"),
              str(d, "generatedForUser"), str(d, "editedBy"), str(d, "editedByName"),
              instant(d, "editedAt"), boolOrFalse(d, "fallback")
            });
  }

  /** Both narrative child lists, distinguished by which field is being copied. */
  private int narrativeChildren(MongoDatabase db, String field) {
    boolean anomalies = "anomalies".equals(field);
    String sql =
        anomalies
            ? """
              INSERT INTO narrative_anomalies (narrative_doc, narrative_doc_key, metric, severity,
                description) VALUES (?,?,?,?,?)
              """
            : """
              INSERT INTO narrative_cited_figures (narrative_doc, narrative_doc_key, label, value)
              VALUES (?,?,?,?)
              """;
    return copyChildren(
        db,
        "narratives",
        anomalies ? "narrative_anomalies" : "narrative_cited_figures",
        field,
        sql,
        (parent, child, index) ->
            anomalies
                ? new Object[] {
                  str(parent, "_id"), index, str(child, "metric"), str(child, "severity"),
                  truncate(str(child, "description"), 1024)
                }
                : new Object[] {
                  str(parent, "_id"), index, truncate(str(child, "label"), 320),
                  truncate(str(child, "value"), 320)
                });
  }

  private int openPositions(MongoDatabase db) {
    return copy(
        db,
        "open_positions",
        "open_positions",
        """
        INSERT INTO open_positions (id, requisition_id, vertical, department, designation, grade,
          location, approved_count, filled_count, approved_on, target_close_date, status, updated_by,
          updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), str(d, "requisitionId"), str(d, "vertical"), str(d, "department"),
              str(d, "designation"), str(d, "grade"), str(d, "location"),
              intOrZero(d, "approvedCount"), intOrZero(d, "filledCount"), date(d, "approvedOn"),
              date(d, "targetCloseDate"), str(d, "status"), str(d, "updatedBy"),
              instant(d, "updatedAt")
            });
  }

  private int ingestRuns(MongoDatabase db) {
    return copy(
        db,
        "ingest_runs",
        "ingest_runs",
        """
        INSERT INTO ingest_runs (id, started_at, finished_at, source_directory, counts_json,
          warnings_json, data_as_of_date, status)
        VALUES (?,?,?,?,?,?,?,?)
        """,
        d ->
            new Object[] {
              str(d, "_id"), instant(d, "startedAt"), instant(d, "finishedAt"),
              str(d, "sourceDirectory"), jsonObject(d, "counts"), jsonArray(d, "warnings"),
              str(d, "dataAsOfDate"), str(d, "status")
            });
  }

  // ---------------------------------------------------------------- copy plumbing

  /** Empties the table, then streams the collection into it in batches. */
  private int copy(
      MongoDatabase db,
      String collection,
      String table,
      String sql,
      Function<Document, Object[]> row) {
    jdbc.update("DELETE FROM " + table);
    List<Object[]> batch = new ArrayList<>(BATCH);
    int written = 0;
    for (Document d : db.getCollection(collection).find().batchSize(BATCH)) {
      batch.add(row.apply(d));
      if (batch.size() >= BATCH) {
        written += flush(sql, batch);
      }
    }
    written += flush(sql, batch);
    log.info("  {} -> {} rows", table, written);
    return written;
  }

  /**
   * Streams one array field out of a collection into a child table.
   *
   * <p>The child table is not emptied here: its parent was just emptied and the foreign keys cascade,
   * so it is already empty. Emptying it again would only be a second full-table delete.
   */
  private int copyChildren(MongoDatabase db, String collection, String table, String field, String sql, ChildRow row) {
    List<Object[]> batch = new ArrayList<>(BATCH);
    int written = 0;
    for (Document parent : db.getCollection(collection).find().batchSize(BATCH)) {
      List<?> children = parent.getList(field, Object.class);
      if (children == null) {
        continue;
      }
      for (int i = 0; i < children.size(); i++) {
        if (children.get(i) instanceof Document child) {
          batch.add(row.build(parent, child, i));
        }
      }
      if (batch.size() >= BATCH) {
        written += flush(sql, batch);
      }
    }
    written += flush(sql, batch);
    log.info("  {} -> {} rows", table, written);
    return written;
  }

  private int flush(String sql, List<Object[]> batch) {
    if (batch.isEmpty()) {
      return 0;
    }
    jdbc.batchUpdate(sql, batch);
    int size = batch.size();
    batch.clear();
    return size;
  }

  /** Row counts read back from MySQL, so the log states what is there rather than what was sent. */
  private void verify(Map<String, Integer> written) {
    log.info("Verifying row counts in MySQL:");
    boolean allMatch = true;
    for (var entry : written.entrySet()) {
      Integer actual =
          jdbc.queryForObject("SELECT COUNT(*) FROM " + entry.getKey(), Integer.class);
      boolean match = actual != null && actual.equals(entry.getValue());
      allMatch &= match;
      log.info(
          "  {} inserted={} in table={} {}",
          entry.getKey(),
          entry.getValue(),
          actual,
          match ? "OK" : "MISMATCH");
    }
    if (!allMatch) {
      throw new IllegalStateException(
          "Migration row counts do not match what was inserted; MySQL is not a faithful copy.");
    }
    log.info("All row counts match.");
  }

  @FunctionalInterface
  private interface ChildRow {
    Object[] build(Document parent, Document child, int index);
  }

  // ---------------------------------------------------------------- BSON readers
  //
  // Everything is read defensively. LocalDate was stored as an ISO string by the Mongo converter, but
  // a document written before that converter existed can still hold a BSON date, so both are handled.

  private static String str(Document d, String key) {
    Object v = d.get(key);
    return v == null ? null : String.valueOf(v);
  }

  private static String truncate(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }

  private static Integer integer(Document d, String key) {
    Object v = d.get(key);
    return v instanceof Number n ? n.intValue() : null;
  }

  private static int intOrZero(Document d, String key) {
    Integer v = integer(d, key);
    return v == null ? 0 : v;
  }

  private static Double dbl(Document d, String key) {
    Object v = d.get(key);
    return v instanceof Number n ? n.doubleValue() : null;
  }

  private static double dblOrZero(Document d, String key) {
    Double v = dbl(d, key);
    return v == null ? 0d : v;
  }

  private static Boolean bool(Document d, String key) {
    Object v = d.get(key);
    return v instanceof Boolean b ? b : null;
  }

  private static boolean boolOrTrue(Document d, String key) {
    Boolean v = bool(d, key);
    return v == null || v;
  }

  private static boolean boolOrFalse(Document d, String key) {
    Boolean v = bool(d, key);
    return v != null && v;
  }

  private static java.sql.Date date(Document d, String key) {
    Object v = d.get(key);
    if (v == null) {
      return null;
    }
    if (v instanceof Date legacy) {
      return new java.sql.Date(legacy.getTime());
    }
    String s = String.valueOf(v);
    if (s.isBlank()) {
      return null;
    }
    try {
      return java.sql.Date.valueOf(LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s));
    } catch (RuntimeException e) {
      log.warn("Unparseable date in {}: {}", key, s);
      return null;
    }
  }

  private static java.sql.Timestamp instant(Document d, String key) {
    Object v = d.get(key);
    if (v instanceof Date date) {
      return new java.sql.Timestamp(date.getTime());
    }
    if (v == null) {
      return null;
    }
    try {
      return java.sql.Timestamp.from(Instant.parse(String.valueOf(v)));
    } catch (RuntimeException e) {
      log.warn("Unparseable instant in {}: {}", key, v);
      return null;
    }
  }

  private static java.sql.Timestamp instantOrNow(Document d, String key) {
    java.sql.Timestamp t = instant(d, key);
    return t == null ? java.sql.Timestamp.from(Instant.now()) : t;
  }

  /** A BSON array of strings, as the JSON text the column holds. */
  private static String jsonArray(Document d, String key) {
    List<?> values = d.getList(key, Object.class);
    if (values == null) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(escape(String.valueOf(values.get(i)))).append('"');
    }
    return sb.append(']').toString();
  }

  /** A BSON sub-document of string -> number, as JSON text. */
  private static String jsonObject(Document d, String key) {
    Object v = d.get(key);
    if (!(v instanceof Document sub)) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (var entry : sub.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
    }
    return sb.append('}').toString();
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
