package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.domain.AttendanceMonth;
import com.leadsquared.peopleinsights.domain.Compensation;
import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.domain.EnpsResponse;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Attrition-risk scoring.
 *
 * <p>The Phase 1 datasets contain no attrition prediction column, so risk is derived here from the
 * signals that are present. It is deliberately a transparent additive rule model rather than a fitted
 * classifier: the register has to show an HRBP the top three contributing factors and stand up in a
 * conversation with the employee's manager, which a black-box score cannot do.
 *
 * <p>Attendance and absence thresholds are <em>relative to the population in view</em>, not absolute.
 * That is not a stylistic choice. In this dataset median attendance is 77.8% and the median employee
 * records nine absent or loss-of-pay days per quarter, so any fixed "below 90%" style threshold flags
 * half the organisation and the register stops being actionable. Comparing each employee against the
 * selection's own distribution keeps the flag meaningful whatever the underlying attendance culture,
 * and makes it adapt automatically when real payroll data replaces this extract.
 */
@Service
public class RiskScoringService {

  /** Score at or above this is High risk; at or above {@link #MEDIUM_THRESHOLD} is Medium. */
  private static final int HIGH_THRESHOLD = 70;

  private static final int MEDIUM_THRESHOLD = 45;

  /** Latest cycle ratings at or above this count as "high performing". */
  private static final int HIGH_RATING = 4;

  /** Tenure beyond which never having been promoted becomes a signal. */
  private static final double STAGNATION_YEARS = 5.0;

  public record Factor(String code, String label, String detail, int weight) {}

  public record Assessment(
      String employeeId,
      String fullName,
      String grade,
      String designation,
      String department,
      String vertical,
      Double tenureYears,
      int score,
      String band,
      List<Factor> factors,
      /** All factors, for the drill-down; {@code factors} is the top three. */
      List<Factor> allFactors) {

    public boolean isHigh() {
      return "High".equals(band);
    }

    public boolean isMediumOrHigh() {
      return "High".equals(band) || "Medium".equals(band);
    }
  }

  /**
   * Population thresholds for the relative signals, derived from the selection in view.
   *
   * @param attendanceP10 attendance rate at the 10th percentile — below this is severe
   * @param attendanceP25 attendance rate at the 25th percentile
   * @param absenceP90 absent-plus-LOP days at the 90th percentile over the trailing quarter
   */
  public record Baseline(
      double attendanceP10, double attendanceP25, double absenceP90, int sampleSize) {

    /** Used when a selection is too small for percentiles to mean anything. */
    static Baseline degenerate() {
      return new Baseline(0, 0, Double.MAX_VALUE, 0);
    }

    public boolean usable() {
      return sampleSize >= 20;
    }
  }

  /** Scores every active employee in the dataset, highest risk first. */
  public List<Assessment> assess(Dataset data) {
    Baseline baseline = baseline(data);
    List<Assessment> out = new ArrayList<>();
    for (Employee e : data.activeEmployees()) {
      out.add(assessOne(e, data, baseline));
    }
    out.sort(Comparator.comparingInt(Assessment::score).reversed());
    return out;
  }

  /** The at-risk register: medium and high only, which is what the HRBP is asked to act on. */
  public List<Assessment> register(Dataset data) {
    return assess(data).stream().filter(Assessment::isMediumOrHigh).toList();
  }

  /** Single-employee assessment, deriving the baseline from the same dataset. */
  public Assessment assessOne(Employee e, Dataset data) {
    return assessOne(e, data, baseline(data));
  }

  public Assessment assessOne(Employee e, Dataset data, Baseline baseline) {
    List<Factor> factors = new ArrayList<>();
    LocalDate asOf = data.asOf();

    scorePerformance(e, factors);
    scoreEngagement(data.enpsByEmployee().get(e.employeeId()), factors);
    scoreStagnation(e, asOf, factors);
    scoreAttendance(data.attendance().getOrDefault(e.employeeId(), List.of()), asOf, baseline, factors);
    scoreLeave(data.attendance().getOrDefault(e.employeeId(), List.of()), asOf, factors);
    scoreCompensation(data.compensation().get(e.employeeId()), asOf, factors);

    int score = Math.min(100, factors.stream().mapToInt(Factor::weight).sum());
    String band = score >= HIGH_THRESHOLD ? "High" : score >= MEDIUM_THRESHOLD ? "Medium" : "Low";

    List<Factor> sorted =
        factors.stream().sorted(Comparator.comparingInt(Factor::weight).reversed()).toList();

    return new Assessment(
        e.employeeId(),
        e.fullName(),
        e.grade(),
        e.designation(),
        e.department(),
        e.vertical(),
        round1(FilterSpec.tenureYears(e, asOf)),
        score,
        band,
        sorted.stream().limit(3).toList(),
        sorted);
  }

  // ------------------------------------------------------------------ baseline

  /** Percentile thresholds over the trailing quarter for everyone in the selection. */
  public Baseline baseline(Dataset data) {
    Set<String> window = recentMonthKeys(data.asOf(), 3);

    List<Double> rates = new ArrayList<>();
    List<Double> absences = new ArrayList<>();
    for (List<AttendanceMonth> months : data.attendance().values()) {
      List<AttendanceMonth> recent = months.stream().filter(m -> window.contains(m.yearMonth())).toList();
      if (recent.isEmpty()) {
        continue;
      }
      rates.add(recent.stream().mapToDouble(AttendanceMonth::attendanceRatePct).average().orElse(100));
      absences.add((double) recent.stream().mapToInt(m -> m.absentDays() + m.lopDays()).sum());
    }
    if (rates.size() < 20) {
      return Baseline.degenerate();
    }
    return new Baseline(
        percentile(rates, 0.10), percentile(rates, 0.25), percentile(absences, 0.90), rates.size());
  }

  private static double percentile(List<Double> values, double p) {
    List<Double> sorted = values.stream().sorted().toList();
    int index = (int) Math.floor((sorted.size() - 1) * p);
    return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
  }

  // ------------------------------------------------------------------ individual signals

  private void scorePerformance(Employee e, List<Factor> factors) {
    List<Integer> trend = e.ratingTrend();
    if (trend.size() >= 2) {
      int latest = trend.get(trend.size() - 1);
      int previous = trend.get(trend.size() - 2);
      int drop = previous - latest;
      if (drop >= 2) {
        factors.add(
            new Factor(
                "PMS_STEEP_DECLINE",
                "Steep PMS decline",
                "Rating fell from " + previous + " to " + latest + " in the latest cycle",
                26));
      } else if (drop == 1) {
        factors.add(
            new Factor(
                "PMS_DECLINE",
                "Declining PMS trend",
                "Rating fell from " + previous + " to " + latest + " in the latest cycle",
                18));
      }
    }
    if (!trend.isEmpty() && trend.get(trend.size() - 1) <= 2) {
      factors.add(
          new Factor(
              "PMS_LOW",
              "Low current rating",
              "Latest PMS rating is " + trend.get(trend.size() - 1) + " out of 5",
              16));
    }
  }

  private void scoreEngagement(EnpsResponse enps, List<Factor> factors) {
    if (enps == null || enps.npsScore() == null) {
      return;
    }
    int score = enps.npsScore();
    if (score <= 3) {
      factors.add(
          new Factor(
              "ENPS_STRONG_DETRACTOR",
              "Strong eNPS detractor",
              "Latest eNPS response was " + score + " out of 10",
              24));
    } else if (score <= 6) {
      factors.add(
          new Factor(
              "ENPS_DETRACTOR",
              "eNPS detractor",
              "Latest eNPS response was " + score + " out of 10",
              18));
    }
  }

  /**
   * Sustained high performance without promotion. The brief calls this out explicitly as a flight
   * risk, and it is the one signal where a strong employee looks fine on every other measure.
   */
  private void scoreStagnation(Employee e, LocalDate asOf, List<Factor> factors) {
    if (isHighPerformerNeverPromoted(e)) {
      factors.add(
          new Factor(
              "HIGH_PERF_NO_PROMO",
              "High performer, never promoted",
              "Three consecutive ratings of " + HIGH_RATING + " or above with no promotion",
              22));
    }
    Double tenure = FilterSpec.tenureYears(e, asOf);
    if (tenure != null && tenure >= STAGNATION_YEARS && !everPromoted(e)) {
      factors.add(
          new Factor(
              "NO_PROMO_LONG_TENURE",
              "No promotion in a long tenure",
              String.format("%.1f years of service with no recorded promotion", tenure),
              8));
    }
  }

  /** Three consecutive appraisal cycles rated 4+ with no promotion in any of them. */
  public boolean isHighPerformerNeverPromoted(Employee e) {
    List<Integer> trend = e.ratingTrend();
    if (trend.size() < 3) {
      return false;
    }
    return trend.stream().allMatch(r -> r >= HIGH_RATING) && !everPromoted(e);
  }

  private boolean everPromoted(Employee e) {
    return e.pms() != null && e.pms().stream().anyMatch(Employee.PmsCycle::promoted);
  }

  /**
   * Attendance against the selection's own distribution.
   *
   * <p>Absent and loss-of-pay days are the unplanned-absence signal here rather than leave
   * transactions: in this extract every leave request was filed before the leave started, and the
   * transaction log ends four months before the attendance register, so a transaction-based measure of
   * unplanned absence would silently never fire. The attendance notation set records exactly this state
   * ("Absent — unplanned, no request" and "LOP — no request"), so it is the correct source.
   */
  private void scoreAttendance(
      List<AttendanceMonth> months, LocalDate asOf, Baseline baseline, List<Factor> factors) {
    if (!baseline.usable()) {
      return;
    }
    Set<String> window = recentMonthKeys(asOf, 3);
    List<AttendanceMonth> recent = months.stream().filter(m -> window.contains(m.yearMonth())).toList();
    if (recent.isEmpty()) {
      return;
    }

    double avgRate = recent.stream().mapToDouble(AttendanceMonth::attendanceRatePct).average().orElse(100);
    if (avgRate < baseline.attendanceP10()) {
      factors.add(
          new Factor(
              "ATTENDANCE_SEVERE",
              "Attendance in the lowest decile",
              String.format(
                  "%.0f%% attendance over the last %d months, against a bottom-decile threshold of %.0f%%",
                  avgRate, recent.size(), baseline.attendanceP10()),
              18));
    } else if (avgRate < baseline.attendanceP25()) {
      factors.add(
          new Factor(
              "ATTENDANCE_LOW",
              "Attendance in the lowest quartile",
              String.format(
                  "%.0f%% attendance over the last %d months, against a bottom-quartile threshold of %.0f%%",
                  avgRate, recent.size(), baseline.attendanceP25()),
              12));
    }

    int unplanned = recent.stream().mapToInt(m -> m.absentDays() + m.lopDays()).sum();
    if (unplanned > baseline.absenceP90()) {
      factors.add(
          new Factor(
              "UNPLANNED_ABSENCE",
              "Unplanned absence pattern",
              String.format(
                  "%d absent or loss-of-pay days in the last %d months, above the selection's 90th percentile of %.0f",
                  unplanned, recent.size(), baseline.absenceP90()),
              14));
    }
  }

  /** No approved leave at all over six months — a burnout signal rather than an attrition one. */
  private void scoreLeave(List<AttendanceMonth> months, LocalDate asOf, List<Factor> factors) {
    Set<String> window = recentMonthKeys(asOf, 6);
    List<AttendanceMonth> recent = months.stream().filter(m -> window.contains(m.yearMonth())).toList();
    if (recent.isEmpty()) {
      return;
    }
    if (recent.stream().mapToInt(AttendanceMonth::leaveDays).sum() == 0) {
      factors.add(
          new Factor(
              "ZERO_LEAVE",
              "No leave taken",
              "No approved leave in the last " + recent.size() + " months — burnout signal",
              10));
    }
  }

  private void scoreCompensation(Compensation comp, LocalDate asOf, List<Factor> factors) {
    if (comp == null) {
      return;
    }
    if (comp.compaRatio() != null && comp.compaRatio() < 0.85) {
      factors.add(
          new Factor(
              "COMPA_LOW",
              "Below grade band midpoint",
              String.format("Compa ratio %.2f against the grade band", comp.compaRatio()),
              8));
    }
    if (comp.lastIncrementDate() != null
        && ChronoUnit.MONTHS.between(comp.lastIncrementDate(), asOf) >= 18) {
      factors.add(
          new Factor(
              "NO_INCREMENT",
              "No recent increment",
              ChronoUnit.MONTHS.between(comp.lastIncrementDate(), asOf)
                  + " months since the last increment",
              8));
    }
  }

  // ------------------------------------------------------------------ helpers

  /** The last {@code count} month keys up to and including the as-of month. */
  public static Set<String> recentMonthKeys(LocalDate asOf, int count) {
    YearMonth end = YearMonth.from(asOf);
    Set<String> keys = new LinkedHashSet<>();
    for (int i = 0; i < count; i++) {
      keys.add(end.minusMonths(i).toString());
    }
    return keys;
  }

  private static Double round1(Double v) {
    return v == null ? null : Math.round(v * 10.0) / 10.0;
  }

  /** Band thresholds, surfaced to the UI so the score is interpretable. */
  public int highThreshold() {
    return HIGH_THRESHOLD;
  }

  public int mediumThreshold() {
    return MEDIUM_THRESHOLD;
  }
}
