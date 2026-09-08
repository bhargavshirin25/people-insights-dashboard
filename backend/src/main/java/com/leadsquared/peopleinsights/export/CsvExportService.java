package com.leadsquared.peopleinsights.export;

import com.leadsquared.peopleinsights.domain.AttendanceMonth;
import com.leadsquared.peopleinsights.domain.Compensation;
import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.domain.EnpsResponse;
import com.leadsquared.peopleinsights.domain.LeaveBalance;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.RiskScoringService;
import com.leadsquared.peopleinsights.security.AccessScope;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

/**
 * CSV export of the filtered employee list behind the dashboard — the "filtered datasets (CSV)"
 * half of the reporting requirement, alongside the PDF business review deck.
 *
 * <p>Individual rows are the entire point of this export, so unlike the deck (which carries counts
 * only) it requires HRBP level or above — {@link ExportController} enforces that with
 * {@code ScopeGuard.resolveIndividual} before this class ever runs. Compensation columns are
 * included only when the caller also holds {@code SEE_COMPENSATION}, consistent with every other
 * compensation read in the application: the export gains columns for a more privileged caller
 * rather than ever hiding a value a less privileged one could otherwise infer from a header.
 */
@Service
public class CsvExportService {

  private static final List<String> BASE_HEADERS =
      List.of(
          "Employee ID",
          "Full Name",
          "Business Unit",
          "Grade",
          "Designation",
          "Department",
          "Office Location",
          "Employee Type",
          "Status",
          "Date of Joining",
          "Tenure (Years)",
          "Date of Exit",
          "Exit Type",
          "Latest PMS Rating",
          "Latest eNPS Score",
          "eNPS Category",
          "Leave Utilisation %",
          "Attendance Rate % (Trailing Qtr)",
          "Risk Score",
          "Risk Band",
          "Top Risk Factors");

  private static final List<String> COMPENSATION_HEADERS =
      List.of("Compa Ratio", "Grade Band Position", "Annual Fixed CTC");

  private final RiskScoringService risk;

  public CsvExportService(RiskScoringService risk) {
    this.risk = risk;
  }

  public String fileName(Dataset data) {
    return "people-insights-"
        + data.label().toLowerCase().replaceAll("[^a-z0-9]+", "-")
        + "-"
        + data.asOf()
        + ".csv";
  }

  /** Renders the filtered dataset — one row per employee in scope — as CSV text. */
  public String render(Dataset data, AccessScope scope) {
    boolean withComp = scope.canSeeIndividualCompensation();
    Map<String, RiskScoringService.Assessment> riskByEmployee =
        risk.assess(data).stream()
            .collect(Collectors.toMap(RiskScoringService.Assessment::employeeId, a -> a));

    StringWriter sw = new StringWriter();
    try (CSVPrinter printer = new CSVPrinter(sw, CSVFormat.DEFAULT)) {
      List<String> headers = new ArrayList<>(BASE_HEADERS);
      if (withComp) {
        headers.addAll(COMPENSATION_HEADERS);
      }
      printer.printRecord(headers);

      for (Employee e : data.employees()) {
        printer.printRecord(row(e, data, riskByEmployee.get(e.employeeId()), withComp));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return sw.toString();
  }

  private List<Object> row(
      Employee e, Dataset data, RiskScoringService.Assessment assessment, boolean withComp) {
    List<Object> row = new ArrayList<>();
    row.add(csvSafe(e.employeeId()));
    row.add(csvSafe(e.fullName()));
    row.add(csvSafe(e.vertical()));
    row.add(csvSafe(e.grade()));
    row.add(csvSafe(e.designation()));
    row.add(csvSafe(e.department()));
    row.add(csvSafe(e.officeLocation()));
    row.add(csvSafe(e.employeeType()));
    row.add(csvSafe(e.status()));
    row.add(str(e.dateOfJoining()));
    row.add(str(tenureYears(e, data.asOf())));
    row.add(str(e.dateOfExit()));
    row.add(csvSafe(e.exitType()));

    List<Integer> ratings = e.ratingTrend();
    row.add(ratings.isEmpty() ? "" : ratings.get(ratings.size() - 1));

    EnpsResponse enps = data.enpsByEmployee().get(e.employeeId());
    row.add(enps == null ? "" : str(enps.npsScore()));
    row.add(enps == null ? "" : csvSafe(enps.npsCategory()));

    LeaveBalance leave = data.leaveBalances().get(e.employeeId());
    row.add(leave == null ? "" : str(leave.overallUtilisationPct()));

    row.add(attendanceRate(data, e));

    if (assessment == null) {
      row.add("");
      row.add("");
      row.add("");
    } else {
      row.add(assessment.score());
      row.add(csvSafe(assessment.band()));
      row.add(
          csvSafe(
              assessment.factors().stream()
                  .map(RiskScoringService.Factor::label)
                  .collect(Collectors.joining("; "))));
    }

    if (withComp) {
      Compensation c = data.compensation().get(e.employeeId());
      row.add(c == null || c.compaRatio() == null ? "" : String.format("%.2f", c.compaRatio()));
      row.add(c == null ? "" : csvSafe(c.bandPosition()));
      row.add(
          c == null || c.annualFixedCtc() == null ? "" : String.format("%.0f", c.annualFixedCtc()));
    }
    return row;
  }

  /** Average attendance rate over the same trailing-quarter window the risk model scores against. */
  private String attendanceRate(Dataset data, Employee e) {
    List<AttendanceMonth> months = data.attendance().getOrDefault(e.employeeId(), List.of());
    Set<String> window = RiskScoringService.recentMonthKeys(data.asOf(), 3);
    List<AttendanceMonth> recent = months.stream().filter(m -> window.contains(m.yearMonth())).toList();
    if (recent.isEmpty()) {
      return "";
    }
    double avg = recent.stream().mapToDouble(AttendanceMonth::attendanceRatePct).average().orElse(0);
    return String.format("%.1f", avg);
  }

  private static Double tenureYears(Employee e, LocalDate asOf) {
    if (e.dateOfJoining() == null) {
      return null;
    }
    LocalDate end =
        e.dateOfExit() != null && e.dateOfExit().isBefore(asOf) ? e.dateOfExit() : asOf;
    return ChronoUnit.DAYS.between(e.dateOfJoining(), end) / 365.25;
  }

  private static String str(LocalDate d) {
    return d == null ? "" : d.toString();
  }

  private static String str(Double d) {
    return d == null ? "" : String.format("%.1f", d);
  }

  private static String str(Integer i) {
    return i == null ? "" : String.valueOf(i);
  }

  /**
   * Guards against CSV/formula injection. A cell whose text starts with {@code = + - @} (or a tab
   * or carriage return, which can smuggle one of those past a naive check) is read as a formula by
   * Excel or Sheets the moment the file is opened — potentially reaching a webservice() call or a
   * DDE command with no further action from the reader. Every string field in this export is
   * ingested data (uploaded via the data-source page's file import, among other paths), so it is
   * attacker-reachable and is neutralised here rather than trusted.
   */
  private static String csvSafe(String s) {
    if (s == null || s.isEmpty()) {
      return "";
    }
    char c = s.charAt(0);
    if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') {
      return "'" + s;
    }
    return s;
  }
}
