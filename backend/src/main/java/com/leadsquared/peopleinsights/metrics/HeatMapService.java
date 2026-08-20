package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.security.ScopeGuard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

/**
 * The org-wide heat map: the same headline metrics, one row per BU.
 *
 * <p>Reachable only by the org-wide roles. It is built by loading each BU as its own dataset rather
 * than grouping one combined query, so each row goes through the same code path as that BU's own
 * dashboard and the numbers cannot disagree between the two views.
 */
@Service
public class HeatMapService {

  private final DatasetLoader loader;
  private final MetricsService metrics;
  private final RiskScoringService risk;
  private final LeaveAttendanceService leaveAttendance;
  private final AsOfService asOfService;

  public HeatMapService(
      DatasetLoader loader,
      MetricsService metrics,
      RiskScoringService risk,
      LeaveAttendanceService leaveAttendance,
      AsOfService asOfService) {
    this.loader = loader;
    this.metrics = metrics;
    this.risk = risk;
    this.leaveAttendance = leaveAttendance;
    this.asOfService = asOfService;
  }

  public record HeatMapRow(
      String businessUnit,
      long headcount,
      double attritionRolling3m,
      double attritionYtd,
      Double enps,
      int atRiskCount,
      double atRiskPct,
      double attendanceRatePct,
      int exitsInPeriod) {}

  public record HeatMapView(String asOf, List<HeatMapRow> rows, List<String> metrics) {}

  public HeatMapView build(ScopeGuard.Scoped scoped, FilterSpec filters) {
    // Each BU is an independent build, so they run concurrently rather than one after another —
    // eight sequential org-wide loads is the difference between a two-second view and a slow one.
    List<HeatMapRow> rows =
        scoped.businessUnits().stream()
            .map(bu -> CompletableFuture.supplyAsync(() -> rowFor(scoped, filters, bu)))
            .toList()
            .stream()
            .map(CompletableFuture::join)
            .sorted(Comparator.comparingDouble(HeatMapRow::attritionRolling3m).reversed())
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

    return new HeatMapView(
        asOfService.asOf().toString(),
        rows,
        List.of("headcount", "attritionRolling3m", "attritionYtd", "enps", "atRiskPct", "attendanceRatePct"));
  }

  /**
   * One BU's row, built through the same code path as that BU's own dashboard.
   *
   * <p>The single-BU scope is derived from the caller's already-authorised scope, so this cannot widen
   * access: the loop above only ever visits units the guard returned, whether that is all eight or the
   * two a custom role was granted.
   */
  private HeatMapRow rowFor(ScopeGuard.Scoped scoped, FilterSpec filters, String bu) {
    ScopeGuard.Scoped single = new ScopeGuard.Scoped(scoped.scope(), List.of(bu), bu);
    Dataset data = loader.load(single, filters);
    var headline = metrics.headline(data);
    int atRisk = risk.register(data).size();

    return new HeatMapRow(
        bu,
        data.headcountAt(data.asOf()),
        cardValue(headline, "attritionRolling3m"),
        cardValue(headline, "attritionYtd"),
        cardValueOrNull(headline, "enps"),
        atRisk,
        percent(atRisk, data.activeEmployees().size()),
        leaveAttendance.selectionAttendanceRate(data),
        data.exitsBetween(filters.periodStart(data.asOf()), filters.periodEnd(data.asOf())).size());
  }

  private static double cardValue(MetricsService.Headline headline, String key) {
    Double v = cardValueOrNull(headline, key);
    return v == null ? 0 : v;
  }

  private static Double cardValueOrNull(MetricsService.Headline headline, String key) {
    return headline.cards().stream()
        .filter(c -> c.key().equals(key))
        .findFirst()
        .map(MetricCard::value)
        .orElse(null);
  }

  private static double percent(int part, int whole) {
    return whole == 0 ? 0 : Math.round(part * 1000.0 / whole) / 10.0;
  }
}
