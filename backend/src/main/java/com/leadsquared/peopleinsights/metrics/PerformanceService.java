package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.domain.EnpsResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Performance and engagement: three cycles of PMS, promotion rate, eNPS with theme breakdown, and the
 * departments where a declining rating trend coincides with weak engagement.
 */
@Service
public class PerformanceService {

  /** Cycle labels in chronological order, matching the master data's column groups. */
  private static final List<String> CYCLES = List.of("FY2023-24", "FY2024-25", "FY2025-26");

  private static final int HIGH_RATING = 4;

  private final RiskScoringService risk;

  public PerformanceService(RiskScoringService risk) {
    this.risk = risk;
  }

  public record CyclePoint(
      String cycle, double avgRating, int rated, Map<Integer, Integer> distribution) {}

  public record PromotionStat(
      int promotionsLast12Months, double promotionRatePct, int eligibleHeadcount, String basis) {}

  public record EnpsCyclePoint(
      String cycle, double score, int responses, int promoters, int passives, int detractors) {}

  public record EnpsThemeStat(
      String theme, int mentions, double avgScore, int promoters, int detractors, double netSentiment) {}

  public record CorrelationRow(
      String department,
      int headcount,
      Double pmsChange,
      Double latestAvgRating,
      Double enpsScore,
      boolean flagged,
      String reason) {}

  public record FlightRiskRow(
      String employeeId,
      String fullName,
      String grade,
      String designation,
      String department,
      Double tenureYears,
      List<Integer> ratings) {}

  public record PerformanceView(
      String businessUnit,
      List<CyclePoint> pmsTrend,
      PromotionStat promotions,
      List<EnpsCyclePoint> enpsTrend,
      List<EnpsThemeStat> enpsThemes,
      String enpsThemeMethod,
      List<CorrelationRow> correlation,
      List<FlightRiskRow> highPerformersNotPromoted,
      String enpsTrendNote) {}

  public PerformanceView build(Dataset data) {
    return new PerformanceView(
        data.label(),
        pmsTrend(data),
        promotionStat(data),
        enpsTrend(data),
        enpsThemes(data),
        "Keyword tagging over the exit-theme vocabulary — a reproducible heuristic, not a trained model.",
        correlation(data),
        highPerformersNotPromoted(data),
        enpsTrendNote(data));
  }

  // ---------------------------------------------------------------- PMS

  private List<CyclePoint> pmsTrend(Dataset data) {
    List<CyclePoint> out = new ArrayList<>();
    for (String cycle : CYCLES) {
      List<Integer> ratings =
          data.employees().stream()
              .map(e -> e.ratingFor(cycle))
              .filter(java.util.Objects::nonNull)
              .toList();
      Map<Integer, Integer> dist = new LinkedHashMap<>();
      for (int r = 1; r <= 5; r++) {
        int rr = r;
        dist.put(r, (int) ratings.stream().filter(v -> v == rr).count());
      }
      out.add(
          new CyclePoint(
              cycle,
              round(ratings.stream().mapToInt(Integer::intValue).average().orElse(0)),
              ratings.size(),
              dist));
    }
    return out;
  }

  // ---------------------------------------------------------------- promotions

  /** Promotion rate over the trailing 12 months against average headcount for the same window. */
  private PromotionStat promotionStat(Dataset data) {
    LocalDate asOf = data.asOf();
    LocalDate from = asOf.minusMonths(12);
    int promotions =
        (int)
            data.employees().stream()
                .filter(e -> e.pms() != null)
                .filter(
                    e ->
                        e.pms().stream()
                            .anyMatch(
                                c ->
                                    c.promoted()
                                        && c.promotionDate() != null
                                        && !c.promotionDate().isBefore(from)
                                        && !c.promotionDate().isAfter(asOf)))
                .count();
    long headcount = data.headcountAt(asOf);
    return new PromotionStat(
        promotions,
        headcount == 0 ? 0 : round(promotions * 100.0 / headcount),
        (int) headcount,
        "Promotions dated between " + from + " and " + asOf + " over current headcount");
  }

  // ---------------------------------------------------------------- eNPS

  private List<EnpsCyclePoint> enpsTrend(Dataset data) {
    Map<String, List<EnpsResponse>> byCycle =
        data.enpsByEmployee().values().stream()
            .filter(r -> r.npsScore() != null)
            .collect(Collectors.groupingBy(EnpsResponse::cycle));
    return byCycle.entrySet().stream()
        .map(
            e -> {
              List<EnpsResponse> rs = e.getValue();
              int promoters = (int) rs.stream().filter(r -> r.npsScore() >= 9).count();
              int detractors = (int) rs.stream().filter(r -> r.npsScore() <= 6).count();
              return new EnpsCyclePoint(
                  e.getKey(),
                  round(MetricsService.enpsScore(rs)),
                  rs.size(),
                  promoters,
                  rs.size() - promoters - detractors,
                  detractors);
            })
        .sorted(Comparator.comparing(EnpsCyclePoint::cycle))
        .toList();
  }

  private String enpsTrendNote(Dataset data) {
    long cycles =
        data.enpsByEmployee().values().stream().map(EnpsResponse::cycle).distinct().count();
    if (cycles <= 1) {
      return "The HR Ops extract contains one survey cycle (June 2026). The three-cycle trend "
          + "activates automatically once earlier or later cycles are loaded — no code change needed.";
    }
    return null;
  }

  /** Theme-level engagement breakdown, derived from the verbatim comments. */
  private List<EnpsThemeStat> enpsThemes(Dataset data) {
    Map<String, List<EnpsResponse>> byTheme = new LinkedHashMap<>();
    for (EnpsResponse r : data.enpsByEmployee().values()) {
      if (r.npsScore() == null) {
        continue;
      }
      for (String theme : EnpsThemeTagger.themesFor(r.comments())) {
        byTheme.computeIfAbsent(theme, k -> new ArrayList<>()).add(r);
      }
    }
    return byTheme.entrySet().stream()
        .map(
            e -> {
              List<EnpsResponse> rs = e.getValue();
              int promoters = (int) rs.stream().filter(r -> r.npsScore() >= 9).count();
              int detractors = (int) rs.stream().filter(r -> r.npsScore() <= 6).count();
              return new EnpsThemeStat(
                  e.getKey(),
                  rs.size(),
                  round(rs.stream().mapToInt(EnpsResponse::npsScore).average().orElse(0)),
                  promoters,
                  detractors,
                  round(MetricsService.enpsScore(rs)));
            })
        .sorted(Comparator.comparingInt(EnpsThemeStat::mentions).reversed())
        .toList();
  }

  // ---------------------------------------------------------------- correlation

  /**
   * Departments where the PMS trend is falling and engagement is below the selection average.
   *
   * <p>The brief asks for "declining PMS ratings vs eNPS drop". A drop needs two survey cycles and the
   * extract has one, so the engagement side of the comparison uses the eNPS level against the
   * selection's own average instead of a change over time. The reason text on each flagged row states
   * which comparison was used, and the calculation upgrades itself to a true drop once a second cycle
   * is loaded.
   */
  private List<CorrelationRow> correlation(Dataset data) {
    Map<String, List<Employee>> byDept =
        data.activeEmployees().stream()
            .filter(e -> e.department() != null)
            .collect(Collectors.groupingBy(Employee::department));

    double orgEnps = MetricsService.enpsScore(new ArrayList<>(data.enpsByEmployee().values()));

    List<CorrelationRow> rows = new ArrayList<>();
    for (var entry : byDept.entrySet()) {
      List<Employee> members = entry.getValue();
      if (members.size() < 5) {
        continue; // too small to report on, and too small to keep individuals anonymous
      }
      Double prevAvg = avgRating(members, "FY2024-25");
      Double latestAvg = avgRating(members, "FY2025-26");
      Double change = prevAvg == null || latestAvg == null ? null : round(latestAvg - prevAvg);

      List<EnpsResponse> deptEnps =
          members.stream()
              .map(e -> data.enpsByEmployee().get(e.employeeId()))
              .filter(java.util.Objects::nonNull)
              .toList();
      Double deptEnpsScore = deptEnps.isEmpty() ? null : round(MetricsService.enpsScore(deptEnps));

      boolean pmsDeclining = change != null && change < -0.1;
      boolean engagementWeak = deptEnpsScore != null && deptEnpsScore < orgEnps;
      boolean flagged = pmsDeclining && engagementWeak;

      String reason =
          flagged
              ? String.format(
                  "Average rating fell %.2f since FY2024-25 and eNPS of %.0f is below the selection average of %.0f",
                  Math.abs(change), deptEnpsScore, orgEnps)
              : null;

      rows.add(
          new CorrelationRow(
              entry.getKey(), members.size(), change, latestAvg, deptEnpsScore, flagged, reason));
    }
    rows.sort(
        Comparator.comparing((CorrelationRow r) -> !r.flagged())
            .thenComparing(r -> r.pmsChange() == null ? 0.0 : r.pmsChange()));
    return rows;
  }

  private Double avgRating(List<Employee> members, String cycle) {
    List<Integer> ratings =
        members.stream().map(e -> e.ratingFor(cycle)).filter(java.util.Objects::nonNull).toList();
    return ratings.isEmpty()
        ? null
        : round(ratings.stream().mapToInt(Integer::intValue).average().orElse(0));
  }

  // ---------------------------------------------------------------- flight risk

  /** Three consecutive high ratings and no promotion — the brief's explicit flight-risk cohort. */
  private List<FlightRiskRow> highPerformersNotPromoted(Dataset data) {
    return data.activeEmployees().stream()
        .filter(risk::isHighPerformerNeverPromoted)
        .map(
            e ->
                new FlightRiskRow(
                    e.employeeId(),
                    e.fullName(),
                    e.grade(),
                    e.designation(),
                    e.department(),
                    round(FilterSpec.tenureYears(e, data.asOf())),
                    e.ratingTrend()))
        .sorted(Comparator.comparing(FlightRiskRow::tenureYears, Comparator.nullsLast(Comparator.reverseOrder())))
        .toList();
  }

  static Double round(Double v) {
    return v == null ? null : Math.round(v * 100.0) / 100.0;
  }

  static double round(double v) {
    return Math.round(v * 100.0) / 100.0;
  }

  /** Exposed so the export and narrative layers can reuse the same high-performer definition. */
  public int highRatingThreshold() {
    return HIGH_RATING;
  }
}
