package com.leadsquared.peopleinsights.ingest;

import static com.leadsquared.peopleinsights.ingest.ExcelSupport.date;
import static com.leadsquared.peopleinsights.ingest.ExcelSupport.dbl;
import static com.leadsquared.peopleinsights.ingest.ExcelSupport.intg;
import static com.leadsquared.peopleinsights.ingest.ExcelSupport.isBlank;
import static com.leadsquared.peopleinsights.ingest.ExcelSupport.str;
import static com.leadsquared.peopleinsights.ingest.ExcelSupport.strOrNull;

import com.leadsquared.peopleinsights.config.IngestProperties;
import com.leadsquared.peopleinsights.domain.AttendanceMonth;
import com.leadsquared.peopleinsights.domain.Compensation;
import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.domain.EnpsResponse;
import com.leadsquared.peopleinsights.domain.ExitRecord;
import com.leadsquared.peopleinsights.domain.IngestRun;
import com.leadsquared.peopleinsights.domain.LeaveBalance;
import com.leadsquared.peopleinsights.domain.LeaveTransaction;
import com.leadsquared.peopleinsights.metrics.DatasetCache;
import com.leadsquared.peopleinsights.metrics.DatasetLoader;
import com.leadsquared.peopleinsights.repo.Store;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Loads the seven HR Ops datasets from their workbooks into MySQL.
 *
 * <p>The workbooks are a full snapshot, not a delta, so a run replaces the people tables wholesale.
 * Configuration tables (users, BU assignments, retention actions, audit) are never touched —
 * re-running the loader must not wipe an HRBP's logged actions or the audit trail.
 *
 * <p>Row-level parse failures are collected as warnings rather than aborting the run: one malformed
 * row out of 5,000 should not leave the dashboard with no data at all.
 */
@Service
public class IngestService {

  private static final Logger log = LoggerFactory.getLogger(IngestService.class);

  /** Header and data row offsets, 0-based, verified against the supplied workbooks. */
  private static final int MASTER_HEADER = 0;
  private static final int COMP_HEADER = 3;
  private static final int LEAVE_BAL_HEADER = 3;
  private static final int LEAVE_TXN_HEADER = 1;
  private static final int ENPS_HEADER = 2;
  private static final int EXIT_HEADER = 3;
  private static final int ATT_MONTH_ROW = 1;
  private static final int ATT_DAY_ROW = 2;
  private static final int ATT_DATA_START = 3;

  private static final List<String> PMS_CYCLES = List.of("FY2023-24", "FY2024-25", "FY2025-26");
  private static final DateTimeFormatter MONTH_HEADER =
      DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

  private final IngestProperties props;
  private final Store store;
  private final JdbcTemplate jdbc;
  private final DatasetCache datasets;

  /**
   * Guards against a second run starting while one is in flight. Each run holds every workbook's
   * DOM in memory at once (POI is not streaming here), so two concurrent runs — a slow request
   * followed by an impatient retry — roughly double peak heap and can turn a run that would have
   * fit into an OutOfMemoryError.
   */
  private final AtomicBoolean running = new AtomicBoolean(false);

  public IngestService(
      IngestProperties props, Store store, JdbcTemplate jdbc, DatasetCache datasets) {
    this.props = props;
    this.store = store;
    this.jdbc = jdbc;
    this.datasets = datasets;
  }

  public IngestRun run() {
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "An ingest is already running. Wait for it to finish before starting another.");
    }
    try {
      Instant started = Instant.now();
      File dir = new File(props.getSourceDirectory());
      if (!dir.isDirectory()) {
        throw new IllegalStateException(
            "ingest.source-directory is not a directory: " + dir.getAbsolutePath());
      }
      log.info("Ingesting HR Ops datasets from {}", dir.getAbsolutePath());

      Map<String, Integer> counts = new LinkedHashMap<>();
      List<String> warnings = new ArrayList<>();

      // Employee master first: it is the spine every other dataset is checked against.
      Map<String, Employee> employees = ingestEmployees(dir, counts, warnings);
      ingestCompensation(dir, employees, counts, warnings);
      ingestLeave(dir, employees, counts, warnings);
      String asOf = ingestAttendance(dir, employees, counts, warnings);
      ingestEnps(dir, employees, counts, warnings);
      ingestExits(dir, employees, counts, warnings);

      IngestRun run =
          IngestRun.of(
              UUID.randomUUID().toString(),
              started,
              Instant.now(),
              dir.getAbsolutePath(),
              counts,
              warnings,
              asOf,
              "SUCCESS");
      store.insert(run);

      // The people collections have just been replaced wholesale, so anything assembled from the
      // previous snapshot is now wrong rather than merely old.
      datasets.invalidateAll();

      log.info("Ingest complete: {} (data as of {}), {} warnings", counts, asOf, warnings.size());
      return run;
    } finally {
      running.set(false);
    }
  }

  // ---------------------------------------------------------------- employee master + PMS

  private Map<String, Employee> ingestEmployees(
      File dir, Map<String, Integer> counts, List<String> warnings) {
    Map<String, Employee> byId = new HashMap<>();
    try (Workbook wb = open(dir, props.getEmployeeMasterFile())) {
      Sheet sheet = wb.getSheet("Employee Master Data");
      var h = new ExcelSupport.Header(sheet, MASTER_HEADER);

      int cId = h.require("Employee Id");
      int cFirst = h.index("First Name");
      int cLast = h.index("Last Name");
      int cFull = h.index("Full Name");
      int cHrbp = h.index("HRBP Name");
      int cDoj = h.index("Date of Joining");
      int cEmail = h.index("Company Email ID");
      int cStatus = h.require("Employee Status");
      int cDesig = h.index("Designation Name");
      int cGrade = h.index("Grade");
      int cVertical = h.require("Vertical");
      int cDeptH = h.index("Departments Hierarchy");
      int cSubDiv = h.index("Sub-Division Name");
      int cCc = h.index("Cost Center");
      int cCcId = h.index("Cost Center ID");
      int cMgr = h.index("Direct manager name");
      int cMgrId = h.index("Direct manager employee ID");
      int cL2 = h.index("L2 Manager");
      int cFnHead = h.index("Employee Function Head Name");
      int cFnHeadId = h.index("Employee Function Head Employee ID");
      int cBuHead = h.index("Employee BU Head Name");
      int cBuHeadId = h.index("Employee BU Head Employee ID");
      int cType = h.index("Employee Type");
      int cDoe = h.index("Date Of Exit");
      int cLoc = h.index("Office Location");
      int cBaseLoc = h.index("Base office location");
      int cProbStart = h.index("Probation Start Date");
      int cProbDays = h.index("Probation Period (Days)");
      int cProbEnd = h.index("Probation End Date");
      int cConfirm = h.index("Confirmation Date");
      int cExitType = h.index("Exit Type");

      // Six columns per appraisal cycle, resolved once so the row loop stays cheap.
      Map<String, int[]> pmsCols = new LinkedHashMap<>();
      for (String cycle : PMS_CYCLES) {
        pmsCols.put(
            cycle,
            new int[] {
              h.index("PMS " + cycle + " Rating"),
              h.index("PMS " + cycle + " Date of Appraisal"),
              h.index("PMS " + cycle + " Promoted"),
              h.index("PMS " + cycle + " Pre-Promotion Designation"),
              h.index("PMS " + cycle + " Post-Promotion Designation"),
              h.index("PMS " + cycle + " Date of Promotion")
            });
      }

      List<Employee> batch = new ArrayList<>();
      for (int r = MASTER_HEADER + 1; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (isBlank(row)) {
          continue;
        }
        String id = str(row, cId);
        if (id.isEmpty()) {
          warnings.add("employees: row " + (r + 1) + " has no Employee Id — skipped");
          continue;
        }
        String deptHierarchy = str(row, cDeptH);

        List<Employee.PmsCycle> cycles = new ArrayList<>();
        for (var e : pmsCols.entrySet()) {
          int[] c = e.getValue();
          Integer rating = intg(row, c[0]);
          LocalDate appraisal = date(row, c[1]);
          String promotedRaw = str(row, c[2]);
          if (rating == null && appraisal == null && promotedRaw.isEmpty()) {
            continue; // employee was not in this cycle
          }
          cycles.add(
              new Employee.PmsCycle(
                  e.getKey(),
                  rating,
                  appraisal,
                  "Yes".equalsIgnoreCase(promotedRaw),
                  strOrNull(row, c[3]),
                  strOrNull(row, c[4]),
                  date(row, c[5])));
        }

        Employee emp =
            new Employee(
                id,
                strOrNull(row, cFirst),
                strOrNull(row, cLast),
                strOrNull(row, cFull),
                strOrNull(row, cHrbp),
                date(row, cDoj),
                strOrNull(row, cEmail),
                str(row, cStatus),
                strOrNull(row, cDesig),
                strOrNull(row, cGrade),
                str(row, cVertical),
                deptHierarchy.isEmpty() ? null : deptHierarchy,
                leafDepartment(deptHierarchy),
                strOrNull(row, cSubDiv),
                strOrNull(row, cCc),
                strOrNull(row, cCcId),
                strOrNull(row, cMgr),
                strOrNull(row, cMgrId),
                strOrNull(row, cL2),
                strOrNull(row, cFnHead),
                strOrNull(row, cFnHeadId),
                strOrNull(row, cBuHead),
                strOrNull(row, cBuHeadId),
                strOrNull(row, cType),
                date(row, cDoe),
                strOrNull(row, cLoc),
                strOrNull(row, cBaseLoc),
                date(row, cProbStart),
                intg(row, cProbDays),
                date(row, cProbEnd),
                date(row, cConfirm),
                strOrNull(row, cExitType),
                cycles);
        batch.add(emp);
        byId.put(id, emp);
      }
      replace(Employee.class, "employees", batch, counts);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to ingest employee master data", e);
    }
    return byId;
  }

  /** "Operations > Legal & Compliance" -> "Legal & Compliance". */
  private static String leafDepartment(String hierarchy) {
    if (hierarchy == null || hierarchy.isBlank()) {
      return null;
    }
    int idx = hierarchy.lastIndexOf('>');
    return (idx >= 0 ? hierarchy.substring(idx + 1) : hierarchy).trim();
  }

  // ---------------------------------------------------------------- compensation

  private void ingestCompensation(
      File dir, Map<String, Employee> employees, Map<String, Integer> counts, List<String> warnings) {
    try (Workbook wb = open(dir, props.getCompensationFile())) {
      Sheet sheet = wb.getSheet("Compensation Data");
      var h = new ExcelSupport.Header(sheet, COMP_HEADER);
      int cId = h.require("Employee ID");

      List<Compensation> batch = new ArrayList<>();
      for (int r = COMP_HEADER + 1; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (isBlank(row)) {
          continue;
        }
        String id = str(row, cId);
        if (id.isEmpty()) {
          continue;
        }
        batch.add(
            new Compensation(
                id,
                strOrNull(row, h.index("Full Name")),
                strOrNull(row, h.index("Grade")),
                verticalFor(id, str(row, h.index("Vertical")), employees, warnings, "compensation"),
                strOrNull(row, h.index("Designation")),
                strOrNull(row, h.index("Location")),
                strOrNull(row, h.index("Employee Type")),
                dbl(row, h.index("Annual Fixed CTC")),
                dbl(row, h.index("Annual Variable Target")),
                dbl(row, h.index("Variable % of CTC")),
                dbl(row, h.index("Total Target CTC")),
                dbl(row, h.index("Monthly CTC")),
                dbl(row, h.index("Basic (Monthly")),
                dbl(row, h.index("HRA (Monthly")),
                dbl(row, h.index("Special Allowance")),
                dbl(row, h.index("Employer PF")),
                dbl(row, h.index("Gratuity Provision")),
                dbl(row, h.index("Monthly Gross")),
                dbl(row, h.index("Employee PF Deduction")),
                dbl(row, h.index("Professional Tax")),
                dbl(row, h.index("Est. Monthly In-Hand")),
                dbl(row, h.index("Grade Band Min")),
                dbl(row, h.index("Grade Band Midpoint")),
                dbl(row, h.index("Grade Band Max")),
                dbl(row, h.index("Compa Ratio")),
                strOrNull(row, h.index("Band Position")),
                dbl(row, h.index("Last Increment %")),
                date(row, h.index("Last Increment Date"))));
      }
      replace(Compensation.class, "compensation", batch, counts);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to ingest compensation data", e);
    }
  }

  // ---------------------------------------------------------------- leave

  private void ingestLeave(
      File dir, Map<String, Employee> employees, Map<String, Integer> counts, List<String> warnings) {
    try (Workbook wb = open(dir, props.getLeaveFile())) {
      Sheet bal = wb.getSheet("Leave Balance");
      var hb = new ExcelSupport.Header(bal, LEAVE_BAL_HEADER);
      int cId = hb.require("Employee ID");

      List<LeaveBalance> balances = new ArrayList<>();
      for (int r = LEAVE_BAL_HEADER + 1; r <= bal.getLastRowNum(); r++) {
        Row row = bal.getRow(r);
        if (isBlank(row)) {
          continue;
        }
        String id = str(row, cId);
        if (id.isEmpty()) {
          continue;
        }
        balances.add(
            new LeaveBalance(
                id,
                strOrNull(row, hb.index("Full Name")),
                strOrNull(row, hb.index("Grade")),
                verticalFor(id, str(row, hb.index("Vertical")), employees, warnings, "leave"),
                strOrNull(row, hb.index("Department")),
                strOrNull(row, hb.index("Gender")),
                strOrNull(row, hb.index("Status")),
                date(row, hb.index("Date of Joining")),
                dbl(row, hb.index("EL Opening Balance")),
                dbl(row, hb.index("EL Annual Entitlement")),
                dbl(row, hb.index("EL Total Available")),
                dbl(row, hb.index("EL Taken")),
                dbl(row, hb.index("EL Closing Balance")),
                dbl(row, hb.index("EL Lapsed")),
                dbl(row, hb.index("EL Utilization %")),
                dbl(row, hb.index("SL Annual Entitlement")),
                dbl(row, hb.index("SL Taken")),
                dbl(row, hb.index("SL Balance")),
                dbl(row, hb.index("SL Utilization %")),
                dbl(row, hb.index("SL Lapsed")),
                dbl(row, hb.index("Paternity Entitlement")),
                dbl(row, hb.index("Paternity Taken")),
                dbl(row, hb.index("Paternity Balance")),
                dbl(row, hb.index("Maternity Entitlement")),
                dbl(row, hb.index("Maternity Taken")),
                dbl(row, hb.index("Maternity Balance")),
                dbl(row, hb.index("Total Entitlement")),
                dbl(row, hb.index("Total Taken")),
                dbl(row, hb.index("Overall Utilization %"))));
      }
      replace(LeaveBalance.class, "leave_balances", balances, counts);

      Sheet txn = wb.getSheet("Leave Transactions");
      var ht = new ExcelSupport.Header(txn, LEAVE_TXN_HEADER);
      int tId = ht.require("Transaction ID");
      int tEmp = ht.require("Employee ID");

      List<LeaveTransaction> txns = new ArrayList<>();
      for (int r = LEAVE_TXN_HEADER + 1; r <= txn.getLastRowNum(); r++) {
        Row row = txn.getRow(r);
        if (isBlank(row)) {
          continue;
        }
        String id = str(row, tId);
        String emp = str(row, tEmp);
        if (id.isEmpty() || emp.isEmpty()) {
          continue;
        }
        txns.add(
            new LeaveTransaction(
                id,
                emp,
                strOrNull(row, ht.index("Employee Name")),
                strOrNull(row, ht.index("Grade")),
                verticalFor(emp, str(row, ht.index("Vertical")), employees, warnings, "leave_txn"),
                strOrNull(row, ht.index("Leave Type")),
                date(row, ht.index("From Date")),
                date(row, ht.index("To Date")),
                dbl(row, ht.index("Days")),
                date(row, ht.index("Applied On")),
                strOrNull(row, ht.index("Reason")),
                strOrNull(row, ht.index("Status"))));
      }
      replace(LeaveTransaction.class, "leave_transactions", txns, counts);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to ingest leave records", e);
    }
  }

  // ---------------------------------------------------------------- attendance (wide -> monthly)

  /**
   * Reshapes the 181 day-columns into one document per employee-month.
   *
   * @return the latest date present in the sheet, used as the dashboard's "as of" anchor
   */
  private String ingestAttendance(
      File dir, Map<String, Employee> employees, Map<String, Integer> counts, List<String> warnings) {
    LocalDate latest = null;
    // Attendance is streamed out in chunks rather than collected and handed to replace(), so it
    // has to clear the previous snapshot itself or the employee-month ids collide.
    if (props.isReplaceExisting()) {
      // attendance_days cascades from attendance_months, so the day register goes with it.
      clear("attendance_months");
    }
    try (Workbook wb = open(dir, props.getAttendanceFile())) {
      Sheet sheet = wb.getSheet("Attendance Data");
      Row monthRow = sheet.getRow(ATT_MONTH_ROW);
      Row dayRow = sheet.getRow(ATT_DAY_ROW);
      var h = new ExcelSupport.Header(sheet, ATT_MONTH_ROW);
      int cId = h.require("Emp ID");
      int cName = h.index("Full Name");
      int firstDayCol = Math.max(cId, cName) + 1;

      // The month label is written once per group over merged cells, so carry it forward.
      Map<Integer, LocalDate> colToDate = new LinkedHashMap<>();
      YearMonth current = null;
      for (int c = firstDayCol; c < dayRow.getLastCellNum(); c++) {
        String monthLabel = str(monthRow, c);
        if (!monthLabel.isEmpty()) {
          try {
            current = YearMonth.parse(ExcelSupport.normalise(monthLabel), MONTH_HEADER);
          } catch (Exception e) {
            warnings.add("attendance: unparseable month header '" + monthLabel + "' at column " + c);
          }
        }
        String dayLabel = str(dayRow, c);
        if (current == null || dayLabel.isEmpty()) {
          continue;
        }
        String digits = dayLabel.replaceAll("\\D.*$", "").trim();
        if (digits.isEmpty()) {
          continue;
        }
        try {
          LocalDate d = current.atDay(Integer.parseInt(digits));
          colToDate.put(c, d);
          if (latest == null || d.isAfter(latest)) {
            latest = d;
          }
        } catch (Exception e) {
          warnings.add("attendance: bad day label '" + dayLabel + "' at column " + c);
        }
      }
      if (colToDate.isEmpty()) {
        throw new IllegalStateException("No day columns resolved in the attendance sheet");
      }

      List<MonthWithDays> batch = new ArrayList<>();
      int written = 0;
      for (int r = ATT_DATA_START; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (isBlank(row)) {
          continue;
        }
        String id = str(row, cId);
        if (id.isEmpty()) {
          continue;
        }
        Employee emp = employees.get(id);
        if (emp == null) {
          warnings.add("attendance: " + id + " is not in the employee master — skipped");
          continue;
        }

        // Bucket the row's day cells by month, then fold each bucket into a document.
        Map<YearMonth, List<AttendanceMonth.DayMark>> byMonth = new LinkedHashMap<>();
        for (var entry : colToDate.entrySet()) {
          String code = str(row, entry.getKey());
          if (code.isEmpty()) {
            continue;
          }
          LocalDate d = entry.getValue();
          byMonth
              .computeIfAbsent(YearMonth.from(d), k -> new ArrayList<>())
              .add(
                  new AttendanceMonth.DayMark(
                      d.getDayOfMonth(), d.toString(), code, AttendanceCodes.category(code)));
        }

        for (var entry : byMonth.entrySet()) {
          batch.add(
              new MonthWithDays(
                  buildAttendanceMonth(emp, entry.getKey(), entry.getValue()), entry.getValue()));
        }

        if (batch.size() >= 2000) {
          written += insertAttendance(batch);
          batch.clear();
        }
      }
      if (!batch.isEmpty()) {
        written += insertAttendance(batch);
      }
      counts.put("attendance_months", written);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to ingest attendance records", e);
    }
    return latest == null ? null : latest.toString();
  }

  /**
   * A month row and the day marks it was counted from.
   *
   * <p>The day marks are not part of {@link AttendanceMonth}: they live in their own table and no
   * dashboard read touches them, so they travel beside the row rather than inside it.
   */
  private record MonthWithDays(AttendanceMonth month, List<AttendanceMonth.DayMark> days) {}

  private static AttendanceMonth buildAttendanceMonth(
      Employee emp, YearMonth ym, List<AttendanceMonth.DayMark> days) {
    int present = 0;
    int absent = 0;
    int lop = 0;
    int leave = 0;
    int half = 0;
    int weekOff = 0;
    int singlePunch = 0;
    int regularisations = 0;

    for (var d : days) {
      switch (d.category()) {
        case AttendanceCodes.PRESENT -> present++;
        case AttendanceCodes.ABSENT -> absent++;
        case AttendanceCodes.LOP -> lop++;
        case AttendanceCodes.LEAVE -> leave++;
        case AttendanceCodes.HALF_DAY -> half++;
        case AttendanceCodes.WEEK_OFF -> weekOff++;
        default -> {
          // Unknown notation: counted nowhere, surfaced by the day marks on drill-down.
        }
      }
      if (AttendanceCodes.isSinglePunch(d.code())) {
        singlePunch++;
      }
      if (AttendanceCodes.hasRegularisation(d.code())) {
        regularisations++;
      }
    }

    int workingDays = days.size() - weekOff;
    // A half day counts as half a day of attendance, matching how HR Ops reports the metric.
    double rate = workingDays == 0 ? 0.0 : ((present + half * 0.5) / workingDays) * 100.0;

    return new AttendanceMonth(
        emp.employeeId() + "_" + ym,
        emp.employeeId(),
        emp.fullName(),
        emp.vertical(),
        emp.department(),
        emp.grade(),
        emp.officeLocation(),
        ym.getYear(),
        ym.getMonthValue(),
        ym.toString(),
        present,
        absent,
        lop,
        leave,
        half,
        weekOff,
        singlePunch,
        regularisations,
        workingDays,
        Math.round(rate * 10.0) / 10.0);
  }

  // ---------------------------------------------------------------- eNPS

  private void ingestEnps(
      File dir, Map<String, Employee> employees, Map<String, Integer> counts, List<String> warnings) {
    try (Workbook wb = open(dir, props.getEnpsFile())) {
      Sheet sheet = wb.getSheet("eNPS Data");
      var h = new ExcelSupport.Header(sheet, ENPS_HEADER);
      int cId = h.require("Employee ID");
      int cDate = h.index("Survey Date");

      List<EnpsResponse> batch = new ArrayList<>();
      for (int r = ENPS_HEADER + 1; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (isBlank(row)) {
          continue;
        }
        String id = str(row, cId);
        if (id.isEmpty()) {
          continue;
        }
        LocalDate surveyDate = date(row, cDate);
        String cycle = surveyDate == null ? "unknown" : YearMonth.from(surveyDate).toString();
        batch.add(
            new EnpsResponse(
                id + "_" + cycle,
                id,
                strOrNull(row, h.index("Full Name")),
                verticalFor(id, str(row, h.index("Vertical")), employees, warnings, "enps"),
                strOrNull(row, h.index("Grade")),
                intg(row, h.index("NPS Score")),
                strOrNull(row, h.index("NPS Category")),
                surveyDate,
                cycle,
                strOrNull(row, h.index("Comments"))));
      }
      replace(EnpsResponse.class, "enps_responses", batch, counts);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to ingest eNPS data", e);
    }
  }

  // ---------------------------------------------------------------- exits

  private void ingestExits(
      File dir, Map<String, Employee> employees, Map<String, Integer> counts, List<String> warnings) {
    try (Workbook wb = open(dir, props.getExitFile())) {
      // The anonymised NLP sheet is the source of record: it carries tenure band and overall
      // sentiment, and deliberately omits employee names.
      Sheet sheet = wb.getSheet("Exit Analysis");
      var h = new ExcelSupport.Header(sheet, EXIT_HEADER);
      int cId = h.require("Employee ID");

      List<ExitRecord> batch = new ArrayList<>();
      for (int r = EXIT_HEADER + 1; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (isBlank(row)) {
          continue;
        }
        String id = str(row, cId);
        if (id.isEmpty()) {
          continue;
        }
        List<ExitRecord.Theme> themes = new ArrayList<>();
        for (int t = 1; t <= 3; t++) {
          String name = strOrNull(row, h.index("Theme " + t));
          if (name == null) {
            continue;
          }
          themes.add(
              new ExitRecord.Theme(
                  name,
                  normaliseSentimentScore(dbl(row, h.index("T" + t + " Score"))),
                  strOrNull(row, h.index("T" + t + " Sentiment"))));
        }
        batch.add(
            new ExitRecord(
                id,
                strOrNull(row, h.index("Grade")),
                verticalFor(id, str(row, h.index("Vertical")), employees, warnings, "exit"),
                strOrNull(row, h.index("Department")),
                strOrNull(row, h.index("Designation")),
                strOrNull(row, h.index("Exit Type")),
                date(row, h.index("Date of Joining")),
                date(row, h.index("Date of Exit")),
                dbl(row, h.index("Tenure (Months)")),
                strOrNull(row, h.index("Tenure Band")),
                strOrNull(row, h.index("Primary Exit Reason")),
                themes,
                normaliseSentimentScore(dbl(row, h.index("Overall Score"))),
                strOrNull(row, h.index("Overall Sentiment")),
                strOrNull(row, h.index("Verbatim Quote (Anonymized)"))));
      }
      replace(ExitRecord.class, "exit_records", batch, counts);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to ingest exit analysis data", e);
    }
  }

  /**
   * The two exit sheets disagree on scale: the NLP sheet stores sentiment as 0-1, the summary sheet
   * as 0-100. Normalised to 0-100 so charts and thresholds have one meaning.
   */
  private static Integer normaliseSentimentScore(Double raw) {
    if (raw == null) {
      return null;
    }
    double scaled = raw <= 1.0 ? raw * 100.0 : raw;
    return (int) Math.round(scaled);
  }

  // ---------------------------------------------------------------- helpers

  /**
   * The employee master's vertical wins over a satellite file's copy.
   *
   * <p>BU drives every access decision, so one BU value per employee must be authoritative. A
   * mismatch is recorded as a warning rather than silently accepted.
   */
  private static String verticalFor(
      String employeeId,
      String fileVertical,
      Map<String, Employee> employees,
      List<String> warnings,
      String dataset) {
    Employee emp = employees.get(employeeId);
    if (emp == null) {
      if (warnings.size() < 200) {
        warnings.add(dataset + ": " + employeeId + " not in employee master; using file's own BU");
      }
      return fileVertical;
    }
    if (!fileVertical.isEmpty() && !fileVertical.equals(emp.vertical()) && warnings.size() < 200) {
      warnings.add(
          dataset
              + ": "
              + employeeId
              + " BU mismatch (master="
              + emp.vertical()
              + ", file="
              + fileVertical
              + ") — master wins");
    }
    return emp.vertical();
  }

  /**
   * Opens read-only from the file rather than from a stream: the attendance workbook is ~915k
   * cells, and streaming it through memory as well as parsing it roughly doubles peak heap.
   */
  private Workbook open(File dir, String fileName) throws Exception {
    File f = new File(dir, fileName);
    if (!f.isFile()) {
      throw new IllegalStateException("Expected workbook not found: " + f.getAbsolutePath());
    }
    return new XSSFWorkbook(OPCPackage.open(f, PackageAccess.READ));
  }

  private <T> void replace(
      Class<T> type, String table, List<T> rows, Map<String, Integer> counts) {
    if (props.isReplaceExisting()) {
      clear(table);
    }
    int written = insertBatch(rows, table);
    counts.put(table, written);
    log.info("  {} -> {} rows", table, written);
  }

  /**
   * Empties a table before a full reload.
   *
   * <p>DELETE rather than TRUNCATE: the child tables reference these rows with ON DELETE CASCADE, and
   * TRUNCATE refuses to run on a table another table points at.
   */
  private void clear(String table) {
    int removed = jdbc.update("DELETE FROM " + table);
    if (removed > 0) {
      log.info("  {} -> cleared {} existing rows", table, removed);
    }
  }

  /**
   * Inserts each row through the mapping layer, which also writes any child rows the aggregate owns —
   * an employee's PMS cycles, an exit's themes. Row-at-a-time is the cost of not hand-writing an
   * INSERT per table; an ingest is a batch job measured against the minutes spent parsing the
   * workbooks, not against a request.
   */
  private <T> int insertBatch(List<T> rows, String table) {
    if (rows.isEmpty()) {
      return 0;
    }
    store.insertAll(rows);
    return rows.size();
  }

  /**
   * Attendance months plus their day marks.
   *
   * <p>The per-day register is not part of the AttendanceMonth aggregate — see {@link DatasetLoader} —
   * so it is written here directly, batched, because it is ~900,000 rows for the supplied extract.
   */
  private int insertAttendance(List<MonthWithDays> batch) {
    if (batch.isEmpty()) {
      return 0;
    }
    store.insertAll(batch.stream().map(MonthWithDays::month).toList());

    List<Object[]> dayRows = new ArrayList<>();
    for (MonthWithDays entry : batch) {
      if (entry.days() == null) {
        continue;
      }
      for (AttendanceMonth.DayMark d : entry.days()) {
        dayRows.add(
            new Object[] {entry.month().id(), d.day(), d.date(), d.code(), d.category()});
      }
    }
    if (!dayRows.isEmpty()) {
      jdbc.batchUpdate(
          "INSERT INTO attendance_days (attendance_month_id, day, day_date, code, category)"
              + " VALUES (?, ?, ?, ?, ?)",
          dayRows);
    }
    return batch.size();
  }
}
