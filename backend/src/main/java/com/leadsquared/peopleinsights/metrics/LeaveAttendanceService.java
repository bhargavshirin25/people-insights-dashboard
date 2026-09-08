package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.domain.AttendanceMonth;
import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.domain.LeaveBalance;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Leave and attendance health.
 *
 * <p>Attendance is reported at team level only, never per individual, which is what the brief asks
 * for. The two individual-grain lists it does ask for — employees with no leave at all, and employees
 * with excessive unplanned leave — are named lists, so they are gated on the caller being HRBP or
 * above before they are populated.
 */
@Service
public class LeaveAttendanceService {

  private final RiskScoringService risk;

  public LeaveAttendanceService(RiskScoringService risk) {
    this.risk = risk;
  }

  /** A team needs at least this many people before it is reported, so aggregates stay aggregate. */
  private static final int MIN_TEAM_SIZE = 5;

  /** Standard deviations above the mean at which a team's absence rate is flagged. */
  private static final double ANOMALY_SIGMA = 1.5;

  public record LeaveTypeStat(
      String leaveType, double entitlement, double taken, double utilisationPct, double lapsed) {}

  public record TeamHealth(
      String team,
      int headcount,
      double attendanceHealthScore,
      double presentRatePct,
      double halfDayRatePct,
      double absentRatePct,
      double lopRatePct,
      double leaveRatePct,
      int singlePunchDays,
      int regularisationRequests) {}

  public record AnomalyFlag(
      String team, String metric, double value, double selectionMean, String severity, String detail) {}

  public record EmployeeLeaveRow(
      String employeeId,
      String fullName,
      String grade,
      String department,
      Double value,
      String detail) {}

  public record LeaveAttendanceView(
      String businessUnit,
      List<String> monthsCovered,
      /** Months inside {@code monthsCovered} that the selected Period narrows "Attendance by team" to. */
      List<String> teamHealthMonths,
      List<LeaveTypeStat> leaveUtilisation,
      double lopDaysTotal,
      List<TeamHealth> teamHealth,
      List<AnomalyFlag> anomalies,
      List<EmployeeLeaveRow> zeroLeaveEmployees,
      String zeroLeaveNote,
      List<EmployeeLeaveRow> excessiveUnplannedLeave,
      String unplannedLeaveNote,
      boolean individualListsVisible,
      String individualListsNote) {}

  public LeaveAttendanceView build(Dataset data, boolean canSeeIndividuals) {
    List<TeamHealth> teams = teamHealth(data);
    List<EmployeeLeaveRow> zeroLeave = canSeeIndividuals ? zeroLeave(data) : List.of();
    List<EmployeeLeaveRow> unplanned = canSeeIndividuals ? excessiveUnplanned(data) : List.of();
    List<String> teamHealthMonths =
        data.attendanceMonths().stream().filter(periodMonthKeys(data)::contains).sorted().toList();

    return new LeaveAttendanceView(
        data.label(),
        data.attendanceMonths(),
        teamHealthMonths,
        leaveUtilisation(data),
        lopDays(data),
        teams,
        anomalies(teams),
        zeroLeave,
        zeroLeaveNote(canSeeIndividuals, zeroLeave),
        unplanned,
        unplannedLeaveNote(),
        canSeeIndividuals,
        canSeeIndividuals
            ? null
            : "Named employee lists require HRBP level or above; team aggregates are shown instead.");
  }

  /** Distinguishes "genuinely nobody" from "could not be computed". */
  private String zeroLeaveNote(boolean canSeeIndividuals, List<EmployeeLeaveRow> rows) {
    if (!canSeeIndividuals || !rows.isEmpty()) {
      return null;
    }
    return "No employee in this selection recorded zero approved leave across the last six months, so "
        + "the burnout list is genuinely empty rather than unavailable.";
  }

  private String unplannedLeaveNote() {
    return "Measured from the attendance register's unplanned states (Absent and LOP) rather than from "
        + "leave transactions. Every leave request in the Phase 1 extract was filed before the leave "
        + "began, and the transaction log ends 25-Mar-2026 while attendance runs to 31-Jul-2026, so a "
        + "transaction-based measure would never register anything.";
  }

  // ---------------------------------------------------------------- leave utilisation

  private List<LeaveTypeStat> leaveUtilisation(Dataset data) {
    var balances = data.leaveBalances().values();
    if (balances.isEmpty()) {
      return List.of();
    }
    List<LeaveTypeStat> out = new ArrayList<>();
    out.add(
        stat(
            "Earned Leave (EL)",
            balances.stream().mapToDouble(b -> nz(b.elAnnualEntitlement())).sum(),
            balances.stream().mapToDouble(b -> nz(b.elTaken())).sum(),
            balances.stream().mapToDouble(b -> nz(b.elLapsed())).sum()));
    out.add(
        stat(
            "Sick Leave (SL)",
            balances.stream().mapToDouble(b -> nz(b.slAnnualEntitlement())).sum(),
            balances.stream().mapToDouble(b -> nz(b.slTaken())).sum(),
            balances.stream().mapToDouble(b -> nz(b.slLapsed())).sum()));
    double paternityEnt = balances.stream().mapToDouble(b -> nz(b.paternityEntitlement())).sum();
    if (paternityEnt > 0) {
      out.add(
          stat(
              "Paternity Leave",
              paternityEnt,
              balances.stream().mapToDouble(b -> nz(b.paternityTaken())).sum(),
              0));
    }
    double maternityEnt = balances.stream().mapToDouble(b -> nz(b.maternityEntitlement())).sum();
    if (maternityEnt > 0) {
      out.add(
          stat(
              "Maternity Leave",
              maternityEnt,
              balances.stream().mapToDouble(b -> nz(b.maternityTaken())).sum(),
              0));
    }
    return out;
  }

  private LeaveTypeStat stat(String type, double entitlement, double taken, double lapsed) {
    return new LeaveTypeStat(
        type,
        round(entitlement),
        round(taken),
        entitlement == 0 ? 0 : round(taken * 100.0 / entitlement),
        round(lapsed));
  }

  /** LOP is an attendance state, not a leave balance, so it comes from the attendance register. */
  private double lopDays(Dataset data) {
    Set<String> window = periodMonthKeys(data);
    return data.attendance().values().stream()
        .flatMap(List::stream)
        .filter(m -> window.contains(m.yearMonth()))
        .mapToInt(AttendanceMonth::lopDays)
        .sum();
  }

  /**
   * The attendance months the selected Period covers, intersected with what the extract actually
   * holds (Feb–Jul 2026). A custom range outside that span yields no months, which every caller
   * already treats as "no data" rather than a special case.
   */
  private static Set<String> periodMonthKeys(Dataset data) {
    LocalDate asOf = data.asOf();
    YearMonth from = YearMonth.from(data.filters().periodStart(asOf));
    YearMonth to = YearMonth.from(data.filters().periodEnd(asOf));
    Set<String> keys = new LinkedHashSet<>();
    for (YearMonth cursor = from; !cursor.isAfter(to); cursor = cursor.plusMonths(1)) {
      keys.add(cursor.toString());
    }
    return keys;
  }

  // ---------------------------------------------------------------- team health

  /**
   * Attendance health per team, aggregated across the months the selected Period covers — unlike the
   * headline cards and the risk model, which use their own fixed named windows, this panel is a direct
   * roll-up of the attendance register and has no other natural window than the one the reader picked.
   *
   * <p>The health score is the present-day rate with half days credited at half weight, penalised for
   * loss-of-pay days, which is the state HR treats as most serious.
   */
  private List<TeamHealth> teamHealth(Dataset data) {
    Set<String> window = periodMonthKeys(data);
    Map<String, List<AttendanceMonth>> byTeam = new LinkedHashMap<>();
    Map<String, Set<String>> membersByTeam = new LinkedHashMap<>();
    for (var entry : data.attendance().entrySet()) {
      for (AttendanceMonth m : entry.getValue()) {
        if (!window.contains(m.yearMonth())) {
          continue;
        }
        String team = m.department() == null ? "Unassigned" : m.department();
        byTeam.computeIfAbsent(team, k -> new ArrayList<>()).add(m);
        membersByTeam.computeIfAbsent(team, k -> new java.util.HashSet<>()).add(m.employeeId());
      }
    }

    List<TeamHealth> out = new ArrayList<>();
    for (var entry : byTeam.entrySet()) {
      int headcount = membersByTeam.get(entry.getKey()).size();
      if (headcount < MIN_TEAM_SIZE) {
        continue;
      }
      List<AttendanceMonth> months = entry.getValue();
      double working = months.stream().mapToInt(AttendanceMonth::workingDays).sum();
      if (working <= 0) {
        continue;
      }
      double present = months.stream().mapToInt(AttendanceMonth::presentDays).sum();
      double half = months.stream().mapToInt(AttendanceMonth::halfDays).sum();
      double absent = months.stream().mapToInt(AttendanceMonth::absentDays).sum();
      double lop = months.stream().mapToInt(AttendanceMonth::lopDays).sum();
      double leave = months.stream().mapToInt(AttendanceMonth::leaveDays).sum();

      // Reported separately so the state columns partition working days and sum to 100%: a half day
      // is half worked and half not, and folding it into the present rate hides the unworked half.
      double presentRate = present / working * 100.0;
      double halfDayRate = half / working * 100.0;
      double lopRate = lop / working * 100.0;
      // The score still credits a half day at half weight — a half-attended day is not an absence.
      double health = Math.max(0, presentRate + halfDayRate * 0.5 - lopRate);

      out.add(
          new TeamHealth(
              entry.getKey(),
              headcount,
              round(health),
              round(presentRate),
              round(halfDayRate),
              round(absent / working * 100.0),
              round(lopRate),
              round(leave / working * 100.0),
              months.stream().mapToInt(AttendanceMonth::singlePunchDays).sum(),
              months.stream().mapToInt(AttendanceMonth::regularisationRequests).sum()));
    }
    out.sort(Comparator.comparingDouble(TeamHealth::attendanceHealthScore));
    return out;
  }

  /**
   * Flags teams whose absence or LOP rate sits well above the selection mean.
   *
   * <p>Uses a standard-deviation threshold rather than a fixed percentage so the flag adapts to the
   * selection: a rate that is unremarkable across the org may be a clear outlier inside one BU.
   */
  private List<AnomalyFlag> anomalies(List<TeamHealth> teams) {
    if (teams.size() < 3) {
      return List.of();
    }
    List<AnomalyFlag> flags = new ArrayList<>();
    flags.addAll(
        flagsFor(teams, "LOP rate", TeamHealth::lopRatePct, "loss-of-pay days as a share of working days"));
    flags.addAll(
        flagsFor(teams, "Absence rate", TeamHealth::absentRatePct, "unplanned absence as a share of working days"));
    flags.sort(Comparator.comparingDouble(AnomalyFlag::value).reversed());
    return flags;
  }

  private List<AnomalyFlag> flagsFor(
      List<TeamHealth> teams,
      String metric,
      java.util.function.ToDoubleFunction<TeamHealth> extractor,
      String description) {
    double mean = teams.stream().mapToDouble(extractor).average().orElse(0);
    double variance =
        teams.stream().mapToDouble(t -> Math.pow(extractor.applyAsDouble(t) - mean, 2)).average().orElse(0);
    double sd = Math.sqrt(variance);
    if (sd <= 0.0001) {
      return List.of();
    }
    List<AnomalyFlag> flags = new ArrayList<>();
    for (TeamHealth t : teams) {
      double value = extractor.applyAsDouble(t);
      double sigmas = (value - mean) / sd;
      if (sigmas >= ANOMALY_SIGMA) {
        flags.add(
            new AnomalyFlag(
                t.team(),
                metric,
                round(value),
                round(mean),
                sigmas >= 2.5 ? "High" : sigmas >= 2.0 ? "Medium" : "Low",
                String.format(
                    "%.1f%% versus a selection average of %.1f%% (%.1f standard deviations) — %s",
                    value, mean, sigmas, description)));
      }
    }
    return flags;
  }

  // ---------------------------------------------------------------- individual lists

  /** Employees with no approved leave in the last six months — a burnout signal. */
  private List<EmployeeLeaveRow> zeroLeave(Dataset data) {
    Set<String> window = RiskScoringService.recentMonthKeys(data.asOf(), 6);
    List<EmployeeLeaveRow> out = new ArrayList<>();
    for (Employee e : data.activeEmployees()) {
      List<AttendanceMonth> months =
          data.attendance().getOrDefault(e.employeeId(), List.of()).stream()
              .filter(m -> window.contains(m.yearMonth()))
              .toList();
      if (months.isEmpty()) {
        continue;
      }
      int leaveDays = months.stream().mapToInt(AttendanceMonth::leaveDays).sum();
      if (leaveDays > 0) {
        continue;
      }
      LeaveBalance balance = data.leaveBalances().get(e.employeeId());
      Double available = balance == null ? null : balance.elClosingBalance();
      out.add(
          new EmployeeLeaveRow(
              e.employeeId(),
              e.fullName(),
              e.grade(),
              e.department(),
              available,
              "No approved leave across "
                  + months.size()
                  + " months"
                  + (available == null ? "" : String.format("; %.0f EL days unused", available))));
    }
    out.sort(Comparator.comparing(EmployeeLeaveRow::value, Comparator.nullsLast(Comparator.reverseOrder())));
    return out;
  }

  /**
   * Employees with an unusual volume of unplanned absence over the trailing quarter.
   *
   * <p>Derived from the attendance register rather than the leave log, and thresholded against the
   * selection's own 90th percentile — see {@link RiskScoringService} for why both choices are forced by
   * the shape of this data.
   */
  private List<EmployeeLeaveRow> excessiveUnplanned(Dataset data) {
    RiskScoringService.Baseline baseline = risk.baseline(data);
    if (!baseline.usable()) {
      return List.of();
    }
    Set<String> window = RiskScoringService.recentMonthKeys(data.asOf(), 3);
    List<EmployeeLeaveRow> out = new ArrayList<>();

    for (Employee e : data.activeEmployees()) {
      List<AttendanceMonth> months =
          data.attendance().getOrDefault(e.employeeId(), List.of()).stream()
              .filter(m -> window.contains(m.yearMonth()))
              .toList();
      if (months.isEmpty()) {
        continue;
      }
      int absent = months.stream().mapToInt(AttendanceMonth::absentDays).sum();
      int lop = months.stream().mapToInt(AttendanceMonth::lopDays).sum();
      int total = absent + lop;
      if (total <= baseline.absenceP90()) {
        continue;
      }
      out.add(
          new EmployeeLeaveRow(
              e.employeeId(),
              e.fullName(),
              e.grade(),
              e.department(),
              (double) total,
              String.format(
                  "%d absent and %d loss-of-pay days across %d months, against a selection 90th percentile of %.0f",
                  absent, lop, months.size(), baseline.absenceP90())));
    }
    out.sort(Comparator.comparing(EmployeeLeaveRow::value, Comparator.nullsLast(Comparator.reverseOrder())));
    return out;
  }

  private static double nz(Double v) {
    return v == null ? 0 : v;
  }

  private static double round(double v) {
    return Math.round(v * 10.0) / 10.0;
  }

  /** Aggregated attendance rate for the selection, reused by the narrative and export layers. */
  public double selectionAttendanceRate(Dataset data) {
    double working =
        data.attendance().values().stream().flatMap(List::stream).mapToInt(AttendanceMonth::workingDays).sum();
    if (working <= 0) {
      return 0;
    }
    double present =
        data.attendance().values().stream().flatMap(List::stream).mapToInt(AttendanceMonth::presentDays).sum();
    double half =
        data.attendance().values().stream().flatMap(List::stream).mapToInt(AttendanceMonth::halfDays).sum();
    return round((present + half * 0.5) / working * 100.0);
  }

  /** Attendance rate for a single month key, used for the month-on-month attendance signal. */
  public double attendanceRateForMonth(Dataset data, String yearMonth) {
    List<AttendanceMonth> months =
        data.attendance().values().stream()
            .flatMap(List::stream)
            .filter(m -> yearMonth.equals(m.yearMonth()))
            .toList();
    double working = months.stream().mapToInt(AttendanceMonth::workingDays).sum();
    if (working <= 0) {
      return 0;
    }
    double present = months.stream().mapToInt(AttendanceMonth::presentDays).sum();
    double half = months.stream().mapToInt(AttendanceMonth::halfDays).sum();
    return round((present + half * 0.5) / working * 100.0);
  }
}
