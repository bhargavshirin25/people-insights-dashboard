package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.domain.AttendanceMonth;
import com.leadsquared.peopleinsights.domain.Compensation;
import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.domain.EnpsResponse;
import com.leadsquared.peopleinsights.domain.ExitRecord;
import com.leadsquared.peopleinsights.domain.LeaveBalance;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One request's worth of authorised, filtered data, joined across the seven datasets.
 *
 * <p>Assembled once per request and shared by every metric service, so a view that shows headcount,
 * attrition, eNPS, risk and attendance together reads each collection once rather than once per card.
 * A BU holds roughly 620 employees, so this stays comfortably in memory and avoids seven separate
 * aggregation pipelines that would each need their own BU predicate to be correct.
 */
public record Dataset(
    List<String> businessUnits,
    String label,
    LocalDate asOf,
    FilterSpec filters,
    /** Filtered employees, both active and exited — attrition needs the leavers. */
    List<Employee> employees,
    Map<String, Compensation> compensation,
    Map<String, LeaveBalance> leaveBalances,
    /** Keyed by employee id, months ascending. The per-day detail is not loaded — see DatasetLoader. */
    Map<String, List<AttendanceMonth>> attendance,
    Map<String, EnpsResponse> enpsByEmployee,
    List<ExitRecord> exits) {

  public Set<String> employeeIds() {
    return employees.stream().map(Employee::employeeId).collect(java.util.stream.Collectors.toSet());
  }

  /** Currently active employees — the headcount denominator. */
  public List<Employee> activeEmployees() {
    return employees.stream().filter(Employee::isActive).toList();
  }

  /**
   * Employees on roll at a past date, derived from joining and exit dates rather than the status
   * flag, so month-on-month comparisons are real rather than the same current number twice.
   */
  public long headcountAt(LocalDate date) {
    return employees.stream().filter(e -> onRollAt(e, date)).count();
  }

  public static boolean onRollAt(Employee e, LocalDate date) {
    if (e.dateOfJoining() == null || e.dateOfJoining().isAfter(date)) {
      return false;
    }
    return e.dateOfExit() == null || e.dateOfExit().isAfter(date);
  }

  /** Exits inside an inclusive date window. */
  public List<Employee> exitsBetween(LocalDate from, LocalDate to) {
    return employees.stream()
        .filter(e -> e.dateOfExit() != null)
        .filter(e -> !e.dateOfExit().isBefore(from) && !e.dateOfExit().isAfter(to))
        .toList();
  }

  /** Attendance months present in the data, ascending. */
  public List<String> attendanceMonths() {
    return attendance.values().stream()
        .flatMap(List::stream)
        .map(AttendanceMonth::yearMonth)
        .distinct()
        .sorted()
        .toList();
  }

  public YearMonth asOfMonth() {
    return YearMonth.from(asOf);
  }

  /**
   * The same loaded data evaluated at an earlier anchor, so a month-on-month comparison can be made
   * without re-reading seven collections.
   */
  public Dataset withAsOf(LocalDate newAsOf) {
    return new Dataset(
        businessUnits,
        label,
        newAsOf,
        filters,
        employees,
        compensation,
        leaveBalances,
        attendance,
        enpsByEmployee,
        exits);
  }

  public int size() {
    return employees.size();
  }
}
