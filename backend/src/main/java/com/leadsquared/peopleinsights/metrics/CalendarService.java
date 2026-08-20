package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.domain.Employee;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The 30-day forward HR calendar.
 *
 * <p>Three of the four event types the brief lists are derivable from the master data. Contract
 * renewals are not: the extract records an employee type of "Contractor" but carries no contract end
 * date, so that category is reported as unavailable rather than guessed at from probation or joining
 * dates. {@link CalendarView#unavailableEventTypes} carries the reason to the UI.
 *
 * <p>Every event names an individual, so the event list is individual-grain data and is withheld from
 * roles that may not see it. Blanking the name would not be enough: a promotion on a given date in a
 * given department at a given grade identifies one person in a team of any normal size. Roles below
 * HRBP therefore receive {@link CalendarView#typeCounts} — how many of each event fall in the window —
 * and no rows, which is the aggregate form the access rules allow.
 */
@Service
public class CalendarService {

  private static final int WINDOW_DAYS = 30;

  /** Anniversary milestones worth surfacing, in years. */
  private static final List<Integer> MILESTONE_YEARS = List.of(1, 3, 5, 10);

  public record CalendarEvent(
      LocalDate date,
      /** PROBATION_CONFIRMATION | APPRAISAL_MILESTONE | WORK_ANNIVERSARY */
      String type,
      String typeLabel,
      String title,
      String employeeId,
      String employeeName,
      String grade,
      String department,
      int daysFromNow) {}

  public record UnavailableEventType(String type, String reason) {}

  public record CalendarView(
      String businessUnit,
      LocalDate from,
      LocalDate to,
      /** Empty for roles that may not see individual data — read {@link #eventsVisible} first. */
      List<CalendarEvent> events,
      /** Event type -> count in the window. Populated for every role, including aggregate-only ones. */
      Map<String, Integer> typeCounts,
      /** Type labels for {@link #typeCounts}, so an aggregate-only view can still name the categories. */
      Map<String, String> typeLabels,
      int totalEvents,
      boolean eventsVisible,
      String eventsNote,
      List<UnavailableEventType> unavailableEventTypes) {}

  /**
   * @param canSeeIndividuals whether the caller's role may see names and employee ids. False strips the
   *     event rows and leaves the per-type counts.
   */
  public CalendarView build(Dataset data, boolean canSeeIndividuals) {
    LocalDate from = data.asOf();
    LocalDate to = from.plusDays(WINDOW_DAYS);
    List<CalendarEvent> events = new ArrayList<>();

    for (Employee e : data.activeEmployees()) {
      addProbationConfirmation(e, from, to, events);
      addAppraisalMilestones(e, from, to, events);
      addWorkAnniversaries(e, from, to, events);
    }

    events.sort(Comparator.comparing(CalendarEvent::date).thenComparing(CalendarEvent::employeeName,
        Comparator.nullsLast(Comparator.naturalOrder())));

    // Counts are derived before the rows are withheld, so an aggregate-only role sees true totals
    // rather than zeroes.
    Map<String, Integer> typeCounts = new LinkedHashMap<>();
    Map<String, String> typeLabels = new LinkedHashMap<>();
    for (CalendarEvent event : events) {
      typeCounts.merge(event.type(), 1, Integer::sum);
      typeLabels.putIfAbsent(event.type(), event.typeLabel());
    }

    return new CalendarView(
        data.label(),
        from,
        to,
        canSeeIndividuals ? events : List.of(),
        typeCounts,
        typeLabels,
        events.size(),
        canSeeIndividuals,
        canSeeIndividuals
            ? null
            : "Individual events are withheld for this role. Counts by type are shown instead, because "
                + "an event names one person and its date, grade and team would identify them even "
                + "without the name.",
        List.of(
            new UnavailableEventType(
                "CONTRACT_RENEWAL",
                "The Phase 1 extract records employee type but no contract end date, so contract "
                    + "renewal dates cannot be derived. Adding a contract-end field to the employee "
                    + "master activates this event type.")));
  }

  private void addProbationConfirmation(
      Employee e, LocalDate from, LocalDate to, List<CalendarEvent> events) {
    LocalDate due = e.probationEndDate();
    if (due == null || due.isBefore(from) || due.isAfter(to)) {
      return;
    }
    // Already confirmed on or before the probation end date: nothing outstanding.
    if (e.confirmationDate() != null && !e.confirmationDate().isAfter(due)) {
      return;
    }
    events.add(
        event(
            due,
            "PROBATION_CONFIRMATION",
            "Probation confirmation",
            "Probation ends — confirmation decision due",
            e,
            from));
  }

  private void addAppraisalMilestones(
      Employee e, LocalDate from, LocalDate to, List<CalendarEvent> events) {
    if (e.pms() == null) {
      return;
    }
    for (Employee.PmsCycle cycle : e.pms()) {
      LocalDate appraisal = cycle.appraisalDate();
      if (appraisal != null && !appraisal.isBefore(from) && !appraisal.isAfter(to)) {
        events.add(
            event(
                appraisal,
                "APPRAISAL_MILESTONE",
                "Appraisal milestone",
                cycle.cycle() + " appraisal date",
                e,
                from));
      }
      LocalDate promotion = cycle.promotionDate();
      if (promotion != null && !promotion.isBefore(from) && !promotion.isAfter(to)) {
        events.add(
            event(
                promotion,
                "APPRAISAL_MILESTONE",
                "Appraisal milestone",
                "Promotion effective: "
                    + (cycle.postPromotionDesignation() == null ? cycle.cycle() : cycle.postPromotionDesignation()),
                e,
                from));
      }
    }
  }

  private void addWorkAnniversaries(
      Employee e, LocalDate from, LocalDate to, List<CalendarEvent> events) {
    LocalDate doj = e.dateOfJoining();
    if (doj == null) {
      return;
    }
    for (int years : MILESTONE_YEARS) {
      LocalDate anniversary = doj.plusYears(years);
      if (!anniversary.isBefore(from) && !anniversary.isAfter(to)) {
        events.add(
            event(
                anniversary,
                "WORK_ANNIVERSARY",
                "Work anniversary",
                years + "-year work anniversary",
                e,
                from));
      }
    }
  }

  private CalendarEvent event(
      LocalDate date, String type, String typeLabel, String title, Employee e, LocalDate from) {
    return new CalendarEvent(
        date,
        type,
        typeLabel,
        title,
        e.employeeId(),
        e.fullName(),
        e.grade(),
        e.department(),
        (int) ChronoUnit.DAYS.between(from, date));
  }
}
