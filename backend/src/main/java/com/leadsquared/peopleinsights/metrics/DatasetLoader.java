package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.domain.AttendanceMonth;
import com.leadsquared.peopleinsights.domain.Compensation;
import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.domain.EnpsResponse;
import com.leadsquared.peopleinsights.domain.ExitRecord;
import com.leadsquared.peopleinsights.domain.LeaveBalance;
import com.leadsquared.peopleinsights.repo.AttendanceRepo;
import com.leadsquared.peopleinsights.repo.CompensationRepo;
import com.leadsquared.peopleinsights.repo.EmployeeRepo;
import com.leadsquared.peopleinsights.repo.EnpsRepo;
import com.leadsquared.peopleinsights.repo.ExitRepo;
import com.leadsquared.peopleinsights.repo.LeaveBalanceRepo;
import com.leadsquared.peopleinsights.security.ScopeGuard;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Builds a {@link Dataset} for an already-authorised scope.
 *
 * <p>Takes {@link ScopeGuard.Scoped} rather than a BU string. A caller cannot ask this class for a BU
 * it has not been granted, because the only way to obtain a {@code Scoped} is to pass the guard.
 *
 * <p>Two deliberate restrictions keep an org-wide load viable. Attendance is limited to the months any
 * metric actually looks at, and the per-day register is not read at all — it lives in its own
 * {@code attendance_days} table rather than inside the month row, so the ~900,000 day marks cannot be
 * dragged into a dashboard read by accident. Every attendance metric uses the counts precomputed at
 * ingest. Leave transactions are not loaded either: unplanned absence is measured from the attendance
 * register instead, for the reasons documented on {@link RiskScoringService}.
 */
@Service
public class DatasetLoader {

  /** Longest window any metric looks back over — the risk model's six-month leave check. */
  private static final int ATTENDANCE_MONTHS_NEEDED = 6;

  private final EmployeeRepo employees;
  private final CompensationRepo compensation;
  private final LeaveBalanceRepo leaveBalances;
  private final EnpsRepo enps;
  private final ExitRepo exits;
  private final AttendanceRepo attendance;
  private final AsOfService asOfService;
  private final DatasetCache cache;

  public DatasetLoader(
      EmployeeRepo employees,
      CompensationRepo compensation,
      LeaveBalanceRepo leaveBalances,
      EnpsRepo enps,
      ExitRepo exits,
      AttendanceRepo attendance,
      AsOfService asOfService,
      DatasetCache cache) {
    this.employees = employees;
    this.compensation = compensation;
    this.leaveBalances = leaveBalances;
    this.enps = enps;
    this.exits = exits;
    this.attendance = attendance;
    this.asOfService = asOfService;
    this.cache = cache;
  }

  /**
   * The dataset for an authorised scope, from {@link DatasetCache} when it has been assembled since
   * the last ingest.
   *
   * <p>Eleven call sites reach this method and a single view can be several of them, so the cache sits
   * here rather than at any one caller.
   */
  public Dataset load(ScopeGuard.Scoped scoped, FilterSpec rawFilters) {
    FilterSpec filters = rawFilters == null ? FilterSpec.none() : rawFilters.normalised();
    LocalDate asOf = asOfService.asOf();
    List<String> bus = scoped.businessUnits();
    String label = scoped.label();
    return cache.get(bus, label, asOf, filters, () -> assemble(bus, label, asOf, filters));
  }

  /** Reads and joins the seven collections. Called only on a cache miss. */
  private Dataset assemble(List<String> bus, String label, LocalDate asOf, FilterSpec filters) {
    // The five satellite reads are independent of each other and of the employee read, and each is a
    // separate round trip to a remote cluster. Issued together they cost about as much as the slowest
    // one instead of the sum, which is the difference between a usable and an unusable org-wide view.
    CompletableFuture<List<Employee>> employeesF =
        CompletableFuture.supplyAsync(() -> employees.findByVerticalIn(bus));
    CompletableFuture<List<Compensation>> compF =
        CompletableFuture.supplyAsync(() -> compensation.findByVerticalIn(bus));
    CompletableFuture<List<LeaveBalance>> balancesF =
        CompletableFuture.supplyAsync(() -> leaveBalances.findByVerticalIn(bus));
    CompletableFuture<List<AttendanceMonth>> attF =
        CompletableFuture.supplyAsync(() -> loadAttendance(bus, asOf));
    CompletableFuture<List<EnpsResponse>> enpsF =
        CompletableFuture.supplyAsync(() -> enps.findByVerticalIn(bus));
    CompletableFuture<List<ExitRecord>> exitsF =
        CompletableFuture.supplyAsync(() -> exits.findByVerticalIn(bus));

    CompletableFuture.allOf(employeesF, compF, balancesF, attF, enpsF, exitsF).join();

    List<Employee> filtered =
        employeesF.join().stream().filter(e -> filters.matches(e, asOf)).toList();
    Set<String> ids = filtered.stream().map(Employee::employeeId).collect(Collectors.toSet());

    // Satellite datasets are narrowed by employee id, so filters that a collection cannot express
    // itself (location on exit records, for example) still apply.
    Map<String, Compensation> comp =
        compF.join().stream()
            .filter(c -> ids.contains(c.employeeId()))
            .collect(Collectors.toMap(Compensation::employeeId, Function.identity(), (a, b) -> a));

    Map<String, LeaveBalance> balances =
        balancesF.join().stream()
            .filter(l -> ids.contains(l.employeeId()))
            .collect(Collectors.toMap(LeaveBalance::employeeId, Function.identity(), (a, b) -> a));

    Map<String, List<AttendanceMonth>> att =
        attF.join().stream()
            .filter(a -> ids.contains(a.employeeId()))
            .collect(Collectors.groupingBy(AttendanceMonth::employeeId));

    Map<String, EnpsResponse> enpsByEmployee =
        enpsF.join().stream()
            .filter(r -> ids.contains(r.employeeId()))
            .collect(
                Collectors.toMap(
                    EnpsResponse::employeeId,
                    Function.identity(),
                    // Keep the most recent cycle if an employee ever answers more than one survey.
                    (a, b) -> a.cycle().compareTo(b.cycle()) >= 0 ? a : b));

    List<ExitRecord> exitRecords =
        exitsF.join().stream().filter(x -> ids.contains(x.employeeId())).toList();

    return new Dataset(
        bus, label, asOf, filters, filtered, comp, balances, att, enpsByEmployee, exitRecords);
  }

  /**
   * Attendance for the months in scope.
   *
   * <p>Only the month rows: the day-level register is a separate table and no metric reads it. A future
   * day-level drill-down should fetch one employee-month from {@code attendance_days} on demand rather
   * than widen this query.
   */
  private List<AttendanceMonth> loadAttendance(List<String> bus, LocalDate asOf) {
    Set<String> months = RiskScoringService.recentMonthKeys(asOf, ATTENDANCE_MONTHS_NEEDED);
    return attendance.findByVerticalInAndYearMonthIn(bus, months);
  }
}
