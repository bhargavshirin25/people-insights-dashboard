package com.leadsquared.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadsquared.peopleinsights.domain.AttendanceMonth;
import com.leadsquared.peopleinsights.domain.Compensation;
import com.leadsquared.peopleinsights.domain.EnpsResponse;
import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.domain.LeaveBalance;
import com.leadsquared.peopleinsights.domain.Permission;
import com.leadsquared.peopleinsights.domain.Role;
import com.leadsquared.peopleinsights.export.CsvExportService;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.FilterSpec;
import com.leadsquared.peopleinsights.metrics.RiskScoringService;
import com.leadsquared.peopleinsights.security.AccessScope;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The CSV export — the "filtered datasets (CSV)" half of the reporting requirement, alongside the
 * PDF business review deck.
 *
 * <p>The property worth protecting is the same one every other individual-grain read in the
 * application protects: compensation columns must appear only for a caller who holds
 * {@code SEE_COMPENSATION}, and never leak a value for it by way of an empty-but-present column.
 */
class CsvExportServiceTest {

  private static final LocalDate AS_OF = LocalDate.of(2026, 7, 31);
  private static final String YEAR_MONTH = "2026-07";

  private final CsvExportService csvExport = new CsvExportService(new RiskScoringService());

  private Employee activeEmployee(String id, String name) {
    return new Employee(
        id,
        name.split(" ")[0],
        name.split(" ")[1],
        name,
        "Some HRBP",
        AS_OF.minusYears(3),
        id.toLowerCase() + "@leadsquared.com",
        "Active",
        "Engineer",
        "M2",
        "Engineering",
        "Engineering > Platform",
        "Platform",
        null,
        null,
        null,
        "A Manager",
        "LS00001",
        null,
        null,
        null,
        null,
        null,
        "Regular",
        null,
        "Bengaluru",
        "Bengaluru",
        null,
        null,
        null,
        null,
        null,
        List.of(new Employee.PmsCycle("FY2025-26", 4, AS_OF.minusMonths(2), false, null, null, null)));
  }

  private Employee exitedEmployee(String id, String name) {
    Employee active = activeEmployee(id, name);
    return new Employee(
        active.employeeId(), active.firstName(), active.lastName(), active.fullName(),
        active.hrbpName(), active.dateOfJoining(), active.email(), "Inactive",
        active.designation(), active.grade(), active.vertical(), active.departmentHierarchy(),
        active.department(), active.subDivision(), active.costCenter(), active.costCenterId(),
        active.managerName(), active.managerId(), active.l2Manager(), active.functionHeadName(),
        active.functionHeadId(), active.buHeadName(), active.buHeadId(), active.employeeType(),
        AS_OF.minusDays(10), active.officeLocation(), active.baseOfficeLocation(),
        active.probationStartDate(), active.probationPeriodDays(), active.probationEndDate(),
        active.confirmationDate(), "Voluntary - Regrettable", active.pms());
  }

  private Dataset dataset() {
    Employee withComp = activeEmployee("EMP001", "Asha Rao");
    Employee withoutComp = exitedEmployee("EMP002", "Vikram Shah");

    Compensation compensation =
        new Compensation(
            "EMP001", "Asha Rao", "M2", "Engineering", "Engineer", "Bengaluru", "Regular",
            1800000.0, 200000.0, 10.0, 2000000.0, 150000.0, 75000.0, 30000.0, 15000.0, 18000.0,
            9000.0, 168000.0, 6000.0, 200.0, 145800.0, 1500000.0, 1800000.0, 2100000.0, 1.0,
            "At midpoint", 8.0, AS_OF.minusMonths(6));

    LeaveBalance leave =
        new LeaveBalance(
            "EMP001", "Asha Rao", "M2", "Engineering", "Platform", "Female", "Active",
            AS_OF.minusYears(3), 12.0, 18.0, 30.0, 10.0, 20.0, 0.0, 33.3, 12.0, 2.0, 10.0, 16.7,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 42.0, 12.0, 28.6);

    EnpsResponse enps =
        new EnpsResponse(
            "R1", "EMP001", "Asha Rao", "Engineering", "M2", 9, "Promoter", AS_OF.minusMonths(1),
            "2026-06", "Great place to work");

    AttendanceMonth attendance =
        new AttendanceMonth(
            "A1", "EMP001", "Asha Rao", "Engineering", "Platform", "M2", "Bengaluru", 2026, 7,
            YEAR_MONTH, 20, 1, 0, 2, 0, 4, 0, 0, 21, 92.5);

    return new Dataset(
        List.of("Engineering"),
        "Engineering",
        AS_OF,
        FilterSpec.none(),
        List.of(withComp, withoutComp),
        Map.of("EMP001", compensation),
        Map.of("EMP001", leave),
        Map.of("EMP001", List.of(attendance)),
        Map.of("EMP001", enps),
        List.of());
  }

  private static AccessScope scope(boolean seeCompensation) {
    var permissions =
        seeCompensation
            ? Set.of(Permission.VIEW_OVERVIEW, Permission.SEE_INDIVIDUAL_PII, Permission.SEE_COMPENSATION)
            : Set.of(Permission.VIEW_OVERVIEW, Permission.SEE_INDIVIDUAL_PII);
    return AccessScope.of(
        "hrbp@leadsquared.com", "An HRBP", Role.HRBP, List.of("Engineering"), permissions, false);
  }

  private List<CSVRecord> parse(String csv) throws IOException {
    try (CSVParser parser = CSVParser.parse(new StringReader(csv), CSVFormat.DEFAULT)) {
      return parser.getRecords();
    }
  }

  @Test
  @DisplayName("without SEE_COMPENSATION, no compensation column exists at all")
  void compensationColumnsAbsentWithoutPermission() throws IOException {
    List<CSVRecord> rows = parse(csvExport.render(dataset(), scope(false)));

    CSVRecord header = rows.get(0);
    assertThat(header).doesNotContain("Compa Ratio", "Grade Band Position", "Annual Fixed CTC");
    // Every data row has exactly as many fields as the header — no silently-dangling column.
    rows.forEach(r -> assertThat(r.size()).isEqualTo(header.size()));
  }

  @Test
  @DisplayName("with SEE_COMPENSATION, compensation columns appear and are populated only where data exists")
  void compensationColumnsPresentWithPermission() throws IOException {
    List<CSVRecord> rows = parse(csvExport.render(dataset(), scope(true)));

    CSVRecord header = rows.get(0);
    assertThat(header).contains("Compa Ratio", "Grade Band Position", "Annual Fixed CTC");

    int compaCol = indexOf(header, "Compa Ratio");
    int idCol = indexOf(header, "Employee ID");

    CSVRecord emp001 = rows.stream().filter(r -> "EMP001".equals(r.get(idCol))).findFirst().orElseThrow();
    CSVRecord emp002 = rows.stream().filter(r -> "EMP002".equals(r.get(idCol))).findFirst().orElseThrow();

    assertThat(emp001.get(compaCol)).isEqualTo("1.00");
    // No compensation record for EMP002 — the column exists (uniform width) but is blank, never a
    // fabricated or inherited value.
    assertThat(emp002.get(compaCol)).isEmpty();
  }

  @Test
  @DisplayName("one row per employee in the dataset, an exited employee included with exit fields set")
  void oneRowPerEmployee() throws IOException {
    List<CSVRecord> rows = parse(csvExport.render(dataset(), scope(true)));

    assertThat(rows).hasSize(3); // header + 2 employees

    CSVRecord header = rows.get(0);
    int idCol = indexOf(header, "Employee ID");
    int exitDateCol = indexOf(header, "Date of Exit");
    int exitTypeCol = indexOf(header, "Exit Type");
    int riskBandCol = indexOf(header, "Risk Band");

    CSVRecord active = rows.stream().filter(r -> "EMP001".equals(r.get(idCol))).findFirst().orElseThrow();
    CSVRecord exited = rows.stream().filter(r -> "EMP002".equals(r.get(idCol))).findFirst().orElseThrow();

    assertThat(exited.get(exitDateCol)).isEqualTo(AS_OF.minusDays(10).toString());
    assertThat(exited.get(exitTypeCol)).isEqualTo("Voluntary - Regrettable");
    // Risk is scored for active employees only — an exited employee gets a blank, not a stale score.
    assertThat(exited.get(riskBandCol)).isEmpty();
    assertThat(active.get(riskBandCol)).isIn("Low", "Medium", "High");
  }

  @Test
  @DisplayName("a value that looks like a spreadsheet formula is neutralised, not written verbatim")
  void formulaLikeValuesAreEscaped() throws IOException {
    // Every string field here is ingested data (the data-source page accepts arbitrary file
    // uploads into these very columns), so a value starting with = + - @ must never reach the
    // file unescaped: Excel/Sheets treats it as a formula the moment the export is opened.
    Employee malicious =
        new Employee(
            "EMP009", "=SUM(A1:A9)", "Test", "=SUM(A1:A9) Test", "Some HRBP",
            AS_OF.minusYears(1), "emp009@leadsquared.com", "Active",
            "+1;DDE()", "@Grade", "-Engineering", "Engineering > Platform",
            "=cmd|' /C calc'!A1", null, null, null, "A Manager", "LS00001",
            null, null, null, null, null, "Regular", null,
            "@Bengaluru", "Bengaluru", null, null, null, null, null, List.of());

    Dataset data =
        new Dataset(
            List.of("Engineering"), "Engineering", AS_OF, FilterSpec.none(),
            List.of(malicious), Map.of(), Map.of(), Map.of(), Map.of(), List.of());

    List<CSVRecord> rows = parse(csvExport.render(data, scope(true)));
    CSVRecord header = rows.get(0);
    CSVRecord row = rows.get(1);

    assertThat(row.get(indexOf(header, "Full Name"))).isEqualTo("'=SUM(A1:A9) Test");
    assertThat(row.get(indexOf(header, "Business Unit"))).isEqualTo("'-Engineering");
    assertThat(row.get(indexOf(header, "Designation"))).isEqualTo("'+1;DDE()");
    assertThat(row.get(indexOf(header, "Grade"))).isEqualTo("'@Grade");
    assertThat(row.get(indexOf(header, "Department"))).isEqualTo("'=cmd|' /C calc'!A1");
    assertThat(row.get(indexOf(header, "Office Location"))).isEqualTo("'@Bengaluru");
  }

  @Test
  @DisplayName("the file name carries the scope label and the reporting date")
  void fileNameIsDescriptive() {
    String name = csvExport.fileName(dataset());

    assertThat(name).startsWith("people-insights-engineering-").endsWith("2026-07-31.csv");
  }

  private static int indexOf(CSVRecord header, String column) {
    for (int i = 0; i < header.size(); i++) {
      if (header.get(i).equals(column)) {
        return i;
      }
    }
    throw new AssertionError("Column not found: " + column);
  }
}
