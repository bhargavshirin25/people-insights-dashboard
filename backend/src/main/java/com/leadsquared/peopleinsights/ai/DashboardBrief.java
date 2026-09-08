package com.leadsquared.peopleinsights.ai;

import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.metrics.CalendarService;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.ExitAnalyticsService;
import com.leadsquared.peopleinsights.metrics.LeaveAttendanceService;
import com.leadsquared.peopleinsights.metrics.MetricCard;
import com.leadsquared.peopleinsights.metrics.MetricsService;
import com.leadsquared.peopleinsights.metrics.PerformanceService;
import com.leadsquared.peopleinsights.metrics.RiskScoringService;
import com.leadsquared.peopleinsights.security.AccessScope;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The dashboard, written out as text for the assistant to answer from.
 *
 * <p>This is the assistant's only source of figures. It is assembled from the same services the views
 * render, for the same already-authorised {@link Dataset}, so the assistant cannot cite a number that
 * is not on a page the user can open — and cannot reach a business unit the scope guard did not grant,
 * because it never sees one.
 *
 * <p>Individual-grain detail is gated on {@link AccessScope#canSeeIndividualPii()} the same way the
 * views gate it. That gate is the reason this builds its own text rather than serialising the view
 * objects: a Viewer's brief must not contain names at all, not merely avoid displaying them, since
 * anything in the brief can be repeated back in an answer.
 */
@Service
public class DashboardBrief {

  /** List lengths. Long enough to answer "which are the worst", short enough to leave room to talk. */
  private static final int TOP_N = 10;

  private static final int VERBATIM_N = 8;

  private final MetricsService metrics;
  private final RiskScoringService risk;
  private final ExitAnalyticsService exits;
  private final PerformanceService performance;
  private final LeaveAttendanceService leaveAttendance;
  private final CalendarService calendar;

  public DashboardBrief(
      MetricsService metrics,
      RiskScoringService risk,
      ExitAnalyticsService exits,
      PerformanceService performance,
      LeaveAttendanceService leaveAttendance,
      CalendarService calendar) {
    this.metrics = metrics;
    this.risk = risk;
    this.exits = exits;
    this.performance = performance;
    this.leaveAttendance = leaveAttendance;
    this.calendar = calendar;
  }

  public String build(Dataset data, AccessScope scope) {
    boolean pii = scope.canSeeIndividualPii();
    StringBuilder sb = new StringBuilder();

    header(sb, data, scope, pii);
    overview(sb, data);
    riskRegister(sb, data, pii);
    exitAnalysis(sb, data);
    performanceAndEngagement(sb, data, pii);
    leaveAndAttendance(sb, data, pii);
    hrCalendar(sb, data, pii);
    businessUnitBreakdown(sb, data);
    caveats(sb);

    return sb.toString();
  }

  // ---------------------------------------------------------------- sections

  private void header(StringBuilder sb, Dataset data, AccessScope scope, boolean pii) {
    LocalDate asOf = data.asOf();
    sb.append("# DASHBOARD DATA\n\n");
    sb.append("Reader: ")
        .append(scope.displayName())
        .append(" — role ")
        .append(scope.role().name())
        .append('\n');
    sb.append("Business units this reader may see: ")
        .append(String.join(", ", scope.allowedBus()))
        .append('\n');
    sb.append("Selection currently in view: ").append(data.label()).append('\n');
    sb.append("Reporting date: ")
        .append(asOf)
        .append(" — every period metric is anchored here, not on today's date\n");
    sb.append("Period in view: ")
        .append(data.filters().periodLabel(asOf))
        .append(" (")
        .append(data.filters().periodStart(asOf))
        .append(" to ")
        .append(data.filters().periodEnd(asOf))
        .append(")\n");
    if (data.filters().isEmpty()) {
      sb.append("Active filters: none beyond the business unit and period\n");
    } else {
      sb.append("Active filters: grades=")
          .append(data.filters().grades())
          .append(", locations=")
          .append(data.filters().locations())
          .append(", departments=")
          .append(data.filters().departments())
          .append(", tenure years=")
          .append(data.filters().tenureMinYears())
          .append(" to ")
          .append(data.filters().tenureMaxYears())
          .append('\n');
    }
    sb.append("Employees in the selection: ")
        .append(data.size())
        .append(" (")
        .append(data.activeEmployees().size())
        .append(" active on roll at the reporting date; the rest are leavers, which attrition needs)\n");
    sb.append("Individual-level data available to this reader: ")
        .append(pii ? "yes — names and employee ids may be discussed" : "NO — aggregates only")
        .append('\n');
  }

  private void overview(StringBuilder sb, Dataset data) {
    MetricsService.Headline headline = metrics.headline(data);
    sb.append("\n## METRIC CARDS — overview view (route /)\n");
    for (MetricCard card : headline.cards()) {
      sb.append("- ").append(card.label()).append(": ");
      if (!card.available()) {
        sb.append("not available — ").append(card.unavailableReason()).append('\n');
        continue;
      }
      sb.append(card.displayValue());
      if (card.momAbsolute() != null) {
        sb.append(
            String.format(
                " (month-on-month %+.1f, %+.1f%%, %s)",
                card.momAbsolute(), card.momPercent(), card.direction()));
      } else if (card.unavailableReason() != null) {
        sb.append(" (no prior-period comparison: ").append(card.unavailableReason()).append(')');
      }
      if (card.basis() != null && !card.basis().isBlank()) {
        sb.append(" [basis: ").append(card.basis()).append(']');
      }
      sb.append('\n');
    }
  }

  private void riskRegister(StringBuilder sb, Dataset data, boolean pii) {
    List<RiskScoringService.Assessment> all = risk.assess(data);
    List<RiskScoringService.Assessment> flagged =
        all.stream().filter(RiskScoringService.Assessment::isMediumOrHigh).toList();
    long high = all.stream().filter(RiskScoringService.Assessment::isHigh).count();

    sb.append("\n## ATTRITION RISK REGISTER (route /risk)\n");
    sb.append("Assessed: ")
        .append(all.size())
        .append(" active employees. At risk: ")
        .append(flagged.size())
        .append(" (")
        .append(high)
        .append(" High, ")
        .append(flagged.size() - high)
        .append(" Medium)\n");
    sb.append("Bands: score ")
        .append(risk.highThreshold())
        .append("+ is High, ")
        .append(risk.mediumThreshold())
        .append("+ is Medium. Scores are relative to this selection's own population, so the same "
            + "employee can score differently under a different filter.\n");

    Map<String, Long> factorCounts =
        flagged.stream()
            .flatMap(a -> a.allFactors().stream())
            .collect(
                Collectors.groupingBy(
                    RiskScoringService.Factor::label, LinkedHashMap::new, Collectors.counting()));
    if (!factorCounts.isEmpty()) {
      sb.append("Most common contributing factors across the flagged population: ");
      sb.append(
          factorCounts.entrySet().stream()
              .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
              .limit(TOP_N)
              .map(e -> e.getKey() + " (" + e.getValue() + ")")
              .collect(Collectors.joining("; ")));
      sb.append('\n');
    }

    if (!pii) {
      sb.append(
          "Individual rows withheld: this reader's role sees aggregates only, so no employee on the "
              + "register may be named or identified.\n");
      return;
    }
    sb.append("Highest-scoring individuals:\n");
    flagged.stream()
        .limit(TOP_N)
        .forEach(
            a -> {
              sb.append("- ")
                  .append(a.fullName())
                  .append(" (")
                  .append(a.employeeId())
                  .append("), ")
                  .append(a.grade())
                  .append(", ")
                  .append(a.department())
                  .append(", tenure ")
                  .append(a.tenureYears() == null ? "unknown" : String.format("%.1fy", a.tenureYears()))
                  .append(" — score ")
                  .append(a.score())
                  .append(' ')
                  .append(a.band())
                  .append(": ")
                  .append(
                      a.factors().stream()
                          .map(RiskScoringService.Factor::detail)
                          .collect(Collectors.joining("; ")))
                  .append('\n');
            });
  }

  private void exitAnalysis(StringBuilder sb, Dataset data) {
    ExitAnalyticsService.ExitView view = exits.build(data, null);
    sb.append("\n## EXIT ANALYSIS (route /exit)\n");
    sb.append("Window: ")
        .append(view.periodLabel())
        .append(" (")
        .append(view.periodFrom())
        .append(" to ")
        .append(view.periodTo())
        .append(")\n");
    sb.append("Exits: ")
        .append(view.totalExits())
        .append(" total, ")
        .append(view.voluntaryExits())
        .append(" voluntary, ")
        .append(view.regrettableExits())
        .append(" regrettable. Average tenure at exit ")
        .append(String.format("%.1f months", view.avgTenureMonths()))
        .append('\n');

    if (!view.topThemes().isEmpty()) {
      sb.append("Exit themes:\n");
      view.topThemes().stream()
          .limit(TOP_N)
          .forEach(
              t ->
                  sb.append("- ")
                      .append(t.theme())
                      .append(": ")
                      .append(t.mentions())
                      .append(" mentions, ")
                      .append(String.format("%.1f%%", t.pctOfExits()))
                      .append(" of exits, sentiment ")
                      .append(t.sentiment())
                      .append(String.format(" (avg score %.1f, %.0f%% negative)", t.avgScore(), t.negativePct()))
                      .append('\n'));
    }
    if (!view.exitTypes().isEmpty()) {
      sb.append("Exit types: ")
          .append(
              view.exitTypes().stream()
                  .map(t -> String.format("%s %d (%.1f%%)", t.exitType(), t.count(), t.pct()))
                  .collect(Collectors.joining("; ")))
          .append('\n');
    }
    if (!view.tenureBands().isEmpty()) {
      sb.append("Tenure at exit: ")
          .append(
              view.tenureBands().stream()
                  .map(b -> String.format("%s %d (%.1f%%)", b.tenureBand(), b.count(), b.pct()))
                  .collect(Collectors.joining("; ")))
          .append('\n');
    }
    if (!view.sentimentTrend().isEmpty()) {
      sb.append("Exit sentiment by month: ")
          .append(
              view.sentimentTrend().stream()
                  .map(p -> String.format("%s avg %.1f (%d exits)", p.month(), p.avgScore(), p.exits()))
                  .collect(Collectors.joining("; ")))
          .append('\n');
    }
    if (!view.verbatims().isEmpty()) {
      sb.append("Anonymised exit verbatims (a sample of ")
          .append(view.verbatims().size())
          .append(" available; they carry no identifying field by design):\n");
      view.verbatims().stream()
          .limit(VERBATIM_N)
          .forEach(
              v ->
                  sb.append("- \"")
                      .append(v.quote())
                      .append("\" [")
                      .append(v.theme())
                      .append(", ")
                      .append(v.sentiment())
                      .append(", ")
                      .append(v.tenureBand())
                      .append(", ")
                      .append(v.month())
                      .append("]\n"));
    }
  }

  private void performanceAndEngagement(StringBuilder sb, Dataset data, boolean pii) {
    PerformanceService.PerformanceView view = performance.build(data);
    sb.append("\n## PERFORMANCE & ENGAGEMENT (route /performance)\n");

    if (!view.pmsTrend().isEmpty()) {
      sb.append("PMS cycles: ")
          .append(
              view.pmsTrend().stream()
                  .map(c -> String.format("%s avg %.2f (%d rated)", c.cycle(), c.avgRating(), c.rated()))
                  .collect(Collectors.joining("; ")))
          .append('\n');
    }
    PerformanceService.PromotionStat promo = view.promotions();
    if (promo != null) {
      sb.append("Promotions in the last 12 months: ")
          .append(promo.promotionsLast12Months())
          .append(String.format(" (%.1f%% of %d eligible)", promo.promotionRatePct(), promo.eligibleHeadcount()))
          .append(promo.basis() == null ? "" : " [" + promo.basis() + "]")
          .append('\n');
    }
    if (!view.enpsTrend().isEmpty()) {
      sb.append("eNPS: ")
          .append(
              view.enpsTrend().stream()
                  .map(
                      p ->
                          String.format(
                              "%s score %.0f from %d responses (%d promoters, %d passives, %d detractors)",
                              p.cycle(), p.score(), p.responses(), p.promoters(), p.passives(), p.detractors()))
                  .collect(Collectors.joining("; ")))
          .append('\n');
    }
    if (view.enpsTrendNote() != null && !view.enpsTrendNote().isBlank()) {
      sb.append("eNPS trend note: ").append(view.enpsTrendNote()).append('\n');
    }
    if (!view.enpsThemes().isEmpty()) {
      sb.append("eNPS themes (")
          .append(view.enpsThemeMethod())
          .append("):\n");
      view.enpsThemes().stream()
          .limit(TOP_N)
          .forEach(
              t ->
                  sb.append("- ")
                      .append(t.theme())
                      .append(String.format(
                          ": %d mentions, avg score %.1f, %d promoters, %d detractors, net sentiment %.1f",
                          t.mentions(), t.avgScore(), t.promoters(), t.detractors(), t.netSentiment()))
                      .append('\n'));
    }
    List<PerformanceService.CorrelationRow> flaggedRows =
        view.correlation().stream().filter(PerformanceService.CorrelationRow::flagged).toList();
    sb.append("Departments flagged on performance-versus-engagement: ")
        .append(flaggedRows.isEmpty() ? "none" : String.valueOf(flaggedRows.size()))
        .append('\n');
    flaggedRows.stream()
        .limit(TOP_N)
        .forEach(
            r ->
                sb.append("- ")
                    .append(r.department())
                    .append(" (headcount ")
                    .append(r.headcount())
                    .append("): ")
                    .append(r.reason())
                    .append('\n'));

    sb.append("High performers never promoted: ").append(view.highPerformersNotPromoted().size());
    if (!pii) {
      sb.append(" — the individual list is withheld from this reader's role\n");
      return;
    }
    sb.append('\n');
    view.highPerformersNotPromoted().stream()
        .limit(TOP_N)
        .forEach(
            r ->
                sb.append("- ")
                    .append(r.fullName())
                    .append(" (")
                    .append(r.employeeId())
                    .append("), ")
                    .append(r.grade())
                    .append(", ")
                    .append(r.department())
                    .append(", tenure ")
                    .append(r.tenureYears() == null ? "unknown" : String.format("%.1fy", r.tenureYears()))
                    .append(", ratings ")
                    .append(r.ratings())
                    .append('\n'));
  }

  private void leaveAndAttendance(StringBuilder sb, Dataset data, boolean pii) {
    LeaveAttendanceService.LeaveAttendanceView view = leaveAttendance.build(data, pii);
    sb.append("\n## LEAVE & ATTENDANCE (route /leave-attendance)\n");
    sb.append("Attendance months covered: ")
        .append(String.join(", ", view.monthsCovered()))
        .append('\n');
    sb.append(String.format("Loss-of-pay days in the selection: %.1f%n", view.lopDaysTotal()));

    if (!view.leaveUtilisation().isEmpty()) {
      sb.append("Leave utilisation by type:\n");
      view.leaveUtilisation()
          .forEach(
              l ->
                  sb.append("- ")
                      .append(l.leaveType())
                      .append(String.format(
                          ": entitlement %.1f, taken %.1f, utilisation %.1f%%, lapsed %.1f",
                          l.entitlement(), l.taken(), l.utilisationPct(), l.lapsed()))
                      .append('\n'));
    }
    if (!view.teamHealth().isEmpty()) {
      sb.append("Team attendance health (teams under five people are suppressed), worst first:\n");
      view.teamHealth().stream()
          .sorted(Comparator.comparingDouble(LeaveAttendanceService.TeamHealth::attendanceHealthScore))
          .limit(TOP_N)
          .forEach(
              t ->
                  sb.append("- ")
                      .append(t.team())
                      .append(" (headcount ")
                      .append(t.headcount())
                      .append(String.format(
                          "): health %.1f, present %.1f%%, half-day %.1f%%, absent %.1f%%, LOP %.1f%%, "
                              + "leave %.1f%%, single-punch days %d, regularisations %d",
                          t.attendanceHealthScore(),
                          t.presentRatePct(),
                          t.halfDayRatePct(),
                          t.absentRatePct(),
                          t.lopRatePct(),
                          t.leaveRatePct(),
                          t.singlePunchDays(),
                          t.regularisationRequests()))
                      .append('\n'));
    }
    if (!view.anomalies().isEmpty()) {
      sb.append("Absence anomaly flags:\n");
      view.anomalies().stream()
          .limit(TOP_N)
          .forEach(
              a ->
                  sb.append("- [")
                      .append(a.severity())
                      .append("] ")
                      .append(a.team())
                      .append(" — ")
                      .append(a.metric())
                      .append(String.format(" %.1f against a selection mean of %.1f: ", a.value(), a.selectionMean()))
                      .append(a.detail())
                      .append('\n'));
    }

    sb.append("Employees taking no leave (burnout watch): ").append(view.zeroLeaveEmployees().size());
    sb.append(pii ? "\n" : " — individual list withheld from this reader's role\n");
    if (pii) {
      view.zeroLeaveEmployees().stream()
          .limit(TOP_N)
          .forEach(r -> sb.append("- ").append(employeeRow(r)).append('\n'));
    }
    if (view.zeroLeaveNote() != null && !view.zeroLeaveNote().isBlank()) {
      sb.append("Note: ").append(view.zeroLeaveNote()).append('\n');
    }

    sb.append("Employees with excessive unplanned absence: ").append(view.excessiveUnplannedLeave().size());
    sb.append(pii ? "\n" : " — individual list withheld from this reader's role\n");
    if (pii) {
      view.excessiveUnplannedLeave().stream()
          .limit(TOP_N)
          .forEach(r -> sb.append("- ").append(employeeRow(r)).append('\n'));
    }
    if (view.unplannedLeaveNote() != null && !view.unplannedLeaveNote().isBlank()) {
      sb.append("Note: ").append(view.unplannedLeaveNote()).append('\n');
    }
  }

  private static String employeeRow(LeaveAttendanceService.EmployeeLeaveRow r) {
    return r.fullName()
        + " ("
        + r.employeeId()
        + "), "
        + r.grade()
        + ", "
        + r.department()
        + " — "
        + r.detail();
  }

  private void hrCalendar(StringBuilder sb, Dataset data, boolean pii) {
    CalendarService.CalendarView view = calendar.build(data, pii);
    sb.append("\n## HR CALENDAR — next 30 days from the reporting date (shown on route /)\n");
    sb.append("Window: ").append(view.from()).append(" to ").append(view.to()).append('\n');
    sb.append("Total events: ").append(view.totalEvents()).append('\n');
    view.typeCounts()
        .forEach(
            (type, count) ->
                sb.append("- ")
                    .append(view.typeLabels().getOrDefault(type, type))
                    .append(": ")
                    .append(count)
                    .append('\n'));
    if (!view.unavailableEventTypes().isEmpty()) {
      view.unavailableEventTypes()
          .forEach(
              u ->
                  sb.append("- ")
                      .append(u.type())
                      .append(": not available — ")
                      .append(u.reason())
                      .append('\n'));
    }
    if (!view.eventsVisible()) {
      sb.append("Individual events withheld from this reader's role")
          .append(view.eventsNote() == null ? "" : " — " + view.eventsNote())
          .append('\n');
      return;
    }
    view.events().stream()
        .limit(TOP_N)
        .forEach(
            e ->
                sb.append("- ")
                    .append(e.date())
                    .append(" (in ")
                    .append(e.daysFromNow())
                    .append(" days): ")
                    .append(e.typeLabel())
                    .append(" — ")
                    .append(e.employeeName())
                    .append(" (")
                    .append(e.employeeId())
                    .append("), ")
                    .append(e.department())
                    .append(" — ")
                    .append(e.title())
                    .append('\n'));
  }

  /**
   * Per-BU figures, when the selection spans more than one business unit.
   *
   * <p>Derived from the selection already in hand rather than by loading each business unit
   * separately, so an org-wide reader gets the comparison without the assistant assembling eight more
   * datasets on every message. It is the same axis as the heat map view, at fewer metrics.
   */
  private void businessUnitBreakdown(StringBuilder sb, Dataset data) {
    if (data.businessUnits().size() < 2) {
      return;
    }
    LocalDate from = data.filters().periodStart(data.asOf());
    LocalDate to = data.filters().periodEnd(data.asOf());
    Map<String, Long> activeByBu = countByBu(data.activeEmployees());
    Map<String, Long> exitsByBu = countByBu(data.exitsBetween(from, to));

    sb.append("\n## BY BUSINESS UNIT (the same axis as the org heat map, route /heatmap)\n");
    data.businessUnits()
        .forEach(
            bu ->
                sb.append("- ")
                    .append(bu)
                    .append(": ")
                    .append(activeByBu.getOrDefault(bu, 0L))
                    .append(" active, ")
                    .append(exitsByBu.getOrDefault(bu, 0L))
                    .append(" exits in the period\n"));
  }

  private static Map<String, Long> countByBu(List<Employee> employees) {
    return employees.stream()
        .map(Employee::vertical)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.groupingBy(bu -> bu, Collectors.counting()));
  }

  /**
   * What the extract cannot answer.
   *
   * <p>Without these the assistant will try to be helpful about the four metrics that do not exist in
   * the data, which is exactly the failure the unavailable states on the cards are there to prevent.
   */
  private void caveats(StringBuilder sb) {
    sb.append(
        """

        ## WHAT THIS DATA CANNOT ANSWER
        - Open approved headcount is not in the extract: there is no requisition dataset, so the card \
        reads "not available" until positions are loaded on the admin view.
        - eNPS has one survey cycle only (June 2026), so there is no month-on-month eNPS change and no \
        multi-cycle trend. The performance view compares a team's eNPS level against the selection \
        average instead of a period-on-period drop.
        - Contract renewal dates do not exist in the master, so contract renewals cannot appear on the \
        HR calendar.
        - Unplanned absence is measured from the attendance register, not from leave transactions: \
        every transaction in the extract was applied for before the leave began, and the transaction \
        log ends 25 March 2026 while attendance runs to 31 July 2026.
        - Voluntary attrition excludes involuntary exits and absconding, and every attrition rate is \
        annualised. YTD means the Indian financial year, 1 April to 31 March.
        - Exit sentiment scores are normalised to 0-100. Team-level reporting suppresses teams under \
        five people. The exit view widens to full history when the selected period holds fewer than \
        ten exits, and says so in its period label.

        ## WHERE THINGS ARE IN THE UI
        - / — overview: the six metric cards above, the AI summary, the 30-day HR calendar, deck export
        - /risk — attrition risk register, ranked, with the top three factors per employee and \
        retention-action logging
        - /exit — exit themes, types, tenure bands, sentiment trend, anonymised verbatims by theme
        - /performance — PMS cycles, promotion rate, eNPS and its themes, performance-versus-engagement \
        flags, high performers never promoted
        - /leave-attendance — leave utilisation, team attendance health, absence anomalies, burnout and \
        unplanned-absence lists
        - /heatmap — every business unit on the same metrics (HR Head and CHRO only)
        - /audit — access events and anomalous-access alerts (HR Head, CHRO and Admin)
        - /access — roles, what each may reach, and the addresses that hold them (needs "Manage access")
        - /data-source — the workbook ingest, file imports, polled API sources (needs "Administration")
        - The filter row at the top of every view sets grade, location, tenure and period together, and \
        the selection persists per user.
        """);
  }
}
