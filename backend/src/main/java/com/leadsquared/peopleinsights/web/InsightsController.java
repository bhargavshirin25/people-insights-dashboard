package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.domain.Compensation;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.DatasetLoader;
import com.leadsquared.peopleinsights.metrics.ExitAnalyticsService;
import com.leadsquared.peopleinsights.metrics.LeaveAttendanceService;
import com.leadsquared.peopleinsights.metrics.PerformanceService;
import com.leadsquared.peopleinsights.security.ScopeGuard;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exit analysis, performance and engagement, leave and attendance, and grade-band compensation. */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

  private final ScopeGuard guard;
  private final DatasetLoader loader;
  private final ExitAnalyticsService exitAnalytics;
  private final PerformanceService performance;
  private final LeaveAttendanceService leaveAttendance;

  public InsightsController(
      ScopeGuard guard,
      DatasetLoader loader,
      ExitAnalyticsService exitAnalytics,
      PerformanceService performance,
      LeaveAttendanceService leaveAttendance) {
    this.guard = guard;
    this.loader = loader;
    this.exitAnalytics = exitAnalytics;
    this.performance = performance;
    this.leaveAttendance = leaveAttendance;
  }

  @GetMapping("/exit")
  public ExitAnalyticsService.ExitView exit(
      @RequestParam(required = false) String bu,
      @RequestParam(required = false) String theme,
      @ModelAttribute FilterQuery filters) {
    var scoped = guard.resolve(bu, "EXIT", "VIEW_EXIT_ANALYSIS");
    return exitAnalytics.build(loader.load(scoped, filters.toSpec()), theme);
  }

  @GetMapping("/performance")
  public PerformanceService.PerformanceView performance(
      @RequestParam(required = false) String bu, @ModelAttribute FilterQuery filters) {
    var scoped = guard.resolve(bu, "PERFORMANCE", "VIEW_PERFORMANCE");
    return performance.build(loader.load(scoped, filters.toSpec()));
  }

  @GetMapping("/leave-attendance")
  public LeaveAttendanceService.LeaveAttendanceView leaveAttendance(
      @RequestParam(required = false) String bu, @ModelAttribute FilterQuery filters) {
    var scoped = guard.resolve(bu, "LEAVE", "VIEW_LEAVE_ATTENDANCE");
    Dataset data = loader.load(scoped, filters.toSpec());
    // Named lists are populated only for HRBP and above; everyone else gets team aggregates.
    return leaveAttendance.build(data, scoped.scope().canSeeIndividualPii());
  }

  public record GradeBand(
      String grade,
      int headcount,
      double bandMin,
      double bandMidpoint,
      double bandMax,
      double avgCompaRatio,
      Map<String, Long> bandPositionCounts) {}

  public record CompensationView(
      String businessUnit, List<GradeBand> gradeBands, String disclosure) {}

  /**
   * Grade-level salary bands only.
   *
   * <p>Compensation is the most sensitive field in the dataset, so this endpoint has no individual
   * grain at all — not gated, simply absent. There is no named-employee compensation response anywhere
   * in the API, which also means no comparison between named employees is constructible from it.
   */
  @GetMapping("/compensation-bands")
  public CompensationView compensationBands(
      @RequestParam(required = false) String bu, @ModelAttribute FilterQuery filters) {
    var scoped = guard.resolve(bu, "COMPENSATION", "VIEW_COMPENSATION_BANDS");
    Dataset data = loader.load(scoped, filters.toSpec());

    Map<String, List<Compensation>> byGrade =
        data.compensation().values().stream()
            .filter(c -> c.grade() != null)
            .collect(Collectors.groupingBy(Compensation::grade));

    List<GradeBand> bands =
        byGrade.entrySet().stream()
            // Suppress a grade with too few people: a band summary over two employees is not
            // meaningfully aggregated.
            .filter(e -> e.getValue().size() >= 5)
            .map(
                e -> {
                  List<Compensation> rows = e.getValue();
                  return new GradeBand(
                      e.getKey(),
                      rows.size(),
                      avg(rows, Compensation::gradeBandMin),
                      avg(rows, Compensation::gradeBandMidpoint),
                      avg(rows, Compensation::gradeBandMax),
                      Math.round(avg(rows, Compensation::compaRatio) * 1000) / 1000.0,
                      rows.stream()
                          .filter(c -> c.bandPosition() != null)
                          .collect(Collectors.groupingBy(Compensation::bandPosition, Collectors.counting())));
                })
            .sorted(Comparator.comparing(GradeBand::grade))
            .toList();

    return new CompensationView(
        scoped.label(),
        bands,
        "Aggregated grade bands only. Individual fixed CTC, variable pay and in-hand figures are not "
            + "exposed by this API, and grades with fewer than five employees in scope are suppressed.");
  }

  private static double avg(
      List<Compensation> rows, java.util.function.Function<Compensation, Double> field) {
    return rows.stream()
        .map(field)
        .filter(java.util.Objects::nonNull)
        .mapToDouble(Double::doubleValue)
        .average()
        .orElse(0);
  }
}
