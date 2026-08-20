package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.domain.EnpsResponse;
import com.leadsquared.peopleinsights.domain.OpenPosition;
import com.leadsquared.peopleinsights.repo.OpenPositionRepo;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The above-the-fold metric cards: headcount, voluntary attrition, eNPS, at-risk count and open
 * positions, each with month-on-month movement.
 *
 * <p>Historical comparisons are derived from joining and exit dates rather than the status flag, so
 * "last month's headcount" is genuinely last month's, not this month's number repeated.
 */
@Service
public class MetricsService {

  private final RiskScoringService risk;
  private final OpenPositionRepo openPositions;

  public MetricsService(RiskScoringService risk, OpenPositionRepo openPositions) {
    this.risk = risk;
    this.openPositions = openPositions;
  }

  public record Headline(
      String businessUnit,
      String asOf,
      String periodLabel,
      List<MetricCard> cards,
      int employeesInScope) {}

  public Headline headline(Dataset data) {
    LocalDate asOf = data.asOf();
    LocalDate prevMonthEnd = asOf.withDayOfMonth(1).minusDays(1);

    List<MetricCard> cards = new ArrayList<>();
    cards.add(headcountCard(data, asOf, prevMonthEnd));
    cards.add(attritionRolling3mCard(data, asOf, prevMonthEnd));
    cards.add(attritionYtdCard(data, asOf));
    cards.add(enpsCard(data));
    cards.add(atRiskCard(data, prevMonthEnd));
    cards.add(openPositionsCard(data));

    return new Headline(
        data.label(),
        asOf.toString(),
        data.filters().periodLabel(asOf),
        cards,
        data.size());
  }

  // ---------------------------------------------------------------- headcount

  private MetricCard headcountCard(Dataset data, LocalDate asOf, LocalDate prevMonthEnd) {
    long current = data.headcountAt(asOf);
    long previous = data.headcountAt(prevMonthEnd);
    return MetricCard.of(
        "headcount",
        "Current headcount",
        current,
        String.valueOf(current),
        "employees",
        (double) previous,
        true,
        "Active employees on roll at " + asOf + ", from joining and exit dates");
  }

  // ---------------------------------------------------------------- attrition

  /**
   * Rolling three-month voluntary attrition, annualised.
   *
   * <p>Only "Voluntary - Regrettable" and "Voluntary - Non-Regrettable" count. Involuntary exits and
   * absconding are excluded, since a rate that mixes them in stops being a retention measure.
   */
  private MetricCard attritionRolling3mCard(Dataset data, LocalDate asOf, LocalDate prevMonthEnd) {
    double current = annualisedVoluntaryAttrition(data, asOf.minusMonths(3).plusDays(1), asOf);
    double previous =
        annualisedVoluntaryAttrition(
            data, prevMonthEnd.minusMonths(3).plusDays(1), prevMonthEnd);
    return MetricCard.of(
        "attritionRolling3m",
        "Voluntary attrition (rolling 3-month)",
        current,
        String.format("%.1f%%", current),
        "% annualised",
        previous,
        false,
        "Voluntary exits in the trailing 3 months over average headcount, annualised");
  }

  private MetricCard attritionYtdCard(Dataset data, LocalDate asOf) {
    LocalDate fyStart = FilterSpec.financialYearStart(asOf);
    double current = annualisedVoluntaryAttrition(data, fyStart, asOf);
    LocalDate prevMonthEnd = asOf.withDayOfMonth(1).minusDays(1);
    // Compare against the same financial year measured a month earlier, not the prior year.
    Double previous =
        prevMonthEnd.isBefore(fyStart)
            ? null
            : annualisedVoluntaryAttrition(data, fyStart, prevMonthEnd);
    return MetricCard.of(
        "attritionYtd",
        "Voluntary attrition (YTD)",
        current,
        String.format("%.1f%%", current),
        "% annualised",
        previous,
        false,
        "Voluntary exits since " + fyStart + " over average headcount, annualised");
  }

  private double annualisedVoluntaryAttrition(Dataset data, LocalDate from, LocalDate to) {
    long voluntaryExits =
        data.exitsBetween(from, to).stream().filter(MetricsService::isVoluntary).count();
    double avgHeadcount = averageHeadcount(data, from, to);
    if (avgHeadcount <= 0) {
      return 0.0;
    }
    double months = Math.max(1.0, ChronoUnit.DAYS.between(from, to) / 30.44);
    double annualisationFactor = 12.0 / months;
    return (voluntaryExits / avgHeadcount) * annualisationFactor * 100.0;
  }

  /** Mean of the headcount at each month end in the window, plus the window start. */
  private double averageHeadcount(Dataset data, LocalDate from, LocalDate to) {
    List<Long> samples = new ArrayList<>();
    samples.add(data.headcountAt(from));
    LocalDate cursor = from.withDayOfMonth(1).plusMonths(1).minusDays(1);
    while (!cursor.isAfter(to)) {
      samples.add(data.headcountAt(cursor));
      cursor = cursor.plusMonths(1);
    }
    if (!samples.isEmpty() && !from.equals(to)) {
      samples.add(data.headcountAt(to));
    }
    return samples.stream().mapToLong(Long::longValue).average().orElse(0);
  }

  static boolean isVoluntary(Employee e) {
    return e.exitType() != null && e.exitType().startsWith("Voluntary");
  }

  // ---------------------------------------------------------------- eNPS

  /**
   * eNPS for the latest survey cycle: percentage promoters minus percentage detractors.
   *
   * <p>The extract contains a single cycle (June 2026), so there is no month-on-month movement to
   * show. The card reports the score and states why the comparison is absent rather than showing a
   * zero change, which would read as "no movement" instead of "no prior data".
   */
  private MetricCard enpsCard(Dataset data) {
    List<EnpsResponse> responses =
        data.enpsByEmployee().values().stream().filter(r -> r.npsScore() != null).toList();
    if (responses.isEmpty()) {
      return MetricCard.unavailable("enps", "eNPS score", "No eNPS responses for this selection.");
    }

    Map<String, List<EnpsResponse>> byCycle =
        responses.stream().collect(Collectors.groupingBy(EnpsResponse::cycle));
    List<String> cycles = byCycle.keySet().stream().sorted().toList();
    String latest = cycles.get(cycles.size() - 1);
    double score = enpsScore(byCycle.get(latest));

    if (cycles.size() < 2) {
      return MetricCard.withoutComparison(
          "enps",
          "eNPS score (" + latest + ")",
          score,
          String.format("%+.0f", score),
          "eNPS",
          "Only one survey cycle ("
              + latest
              + ") exists in the HR Ops extract, so no month-on-month change can be computed.",
          true,
          "Promoters minus detractors as a percentage of " + byCycle.get(latest).size() + " responses");
    }

    String prior = cycles.get(cycles.size() - 2);
    return MetricCard.of(
        "enps",
        "eNPS score (" + latest + ")",
        score,
        String.format("%+.0f", score),
        "eNPS",
        enpsScore(byCycle.get(prior)),
        true,
        "Promoters minus detractors, versus cycle " + prior);
  }

  static double enpsScore(List<EnpsResponse> responses) {
    if (responses == null || responses.isEmpty()) {
      return 0;
    }
    long promoters = responses.stream().filter(r -> r.npsScore() != null && r.npsScore() >= 9).count();
    long detractors = responses.stream().filter(r -> r.npsScore() != null && r.npsScore() <= 6).count();
    return ((promoters - detractors) * 100.0) / responses.size();
  }

  // ---------------------------------------------------------------- at-risk

  /**
   * At-risk count now versus the same model evaluated a month earlier.
   *
   * <p>The comparison is partial by nature: performance ratings and eNPS are point-in-time facts that
   * do not change when the anchor moves, so the movement reflects the attendance and leave signals
   * plus joiners and leavers. The basis text says so on the card.
   */
  private MetricCard atRiskCard(Dataset data, LocalDate prevMonthEnd) {
    long current = risk.register(data).size();
    long previous = risk.register(data.withAsOf(prevMonthEnd)).size();
    return MetricCard.of(
        "atRisk",
        "At-risk employees",
        current,
        String.valueOf(current),
        "employees",
        (double) previous,
        false,
        "Medium and high attrition risk; movement reflects attendance, leave and roster changes");
  }

  // ---------------------------------------------------------------- open positions

  /**
   * Approved-but-unfilled headcount. HR Ops supplied no requisition dataset in Phase 1, so this is
   * unavailable until an admin loads positions or an ATS feed is connected.
   */
  private MetricCard openPositionsCard(Dataset data) {
    List<OpenPosition> open = openPositions.findByVerticalInAndStatus(data.businessUnits(), "OPEN");
    if (open.isEmpty()) {
      return MetricCard.unavailable(
          "openPositions",
          "Open approved positions",
          "No requisition dataset was supplied in the Phase 1 extract. An admin can load approved "
              + "positions, or connect an ATS feed, to activate this card.");
    }
    int vacant = open.stream().mapToInt(OpenPosition::vacantCount).sum();
    return MetricCard.withoutComparison(
        "openPositions",
        "Open approved positions",
        vacant,
        String.valueOf(vacant),
        "positions",
        "Requisition history is not retained, so no month-on-month change is available.",
        false,
        open.size() + " open requisitions in scope");
  }
}
