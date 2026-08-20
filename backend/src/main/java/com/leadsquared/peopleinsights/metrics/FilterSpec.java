package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.domain.Employee;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The multi-level filter state: grade band, location, tenure range and time period, all applied
 * simultaneously.
 *
 * <p>Filters narrow the employee set, and every other dataset is then restricted to that set by
 * employee id. That keeps a filter meaningful on collections that do not carry the filtered column —
 * exit records have no location field, for instance, but still respect a location filter because the
 * employees behind them do.
 */
public record FilterSpec(
    List<String> grades,
    List<String> locations,
    Double tenureMinYears,
    Double tenureMaxYears,
    /** LAST_30_DAYS | LAST_QUARTER | YTD | CUSTOM */
    String period,
    LocalDate customFrom,
    LocalDate customTo) {

  public static FilterSpec none() {
    return new FilterSpec(List.of(), List.of(), null, null, "LAST_30_DAYS", null, null);
  }

  public FilterSpec normalised() {
    return new FilterSpec(
        grades == null ? List.of() : grades.stream().filter(s -> !s.isBlank()).toList(),
        locations == null ? List.of() : locations.stream().filter(s -> !s.isBlank()).toList(),
        tenureMinYears,
        tenureMaxYears,
        period == null || period.isBlank() ? "LAST_30_DAYS" : period.toUpperCase(),
        customFrom,
        customTo);
  }

  public boolean isEmpty() {
    return grades.isEmpty() && locations.isEmpty() && tenureMinYears == null && tenureMaxYears == null;
  }

  /** Inclusive start of the selected period, relative to the dashboard's as-of anchor. */
  public LocalDate periodStart(LocalDate asOf) {
    return switch (period == null ? "LAST_30_DAYS" : period.toUpperCase()) {
      case "LAST_QUARTER" -> asOf.minusMonths(3).plusDays(1);
      case "YTD" -> financialYearStart(asOf);
      case "CUSTOM" -> customFrom != null ? customFrom : asOf.minusDays(29);
      default -> asOf.minusDays(29);
    };
  }

  /** Inclusive end of the selected period. */
  public LocalDate periodEnd(LocalDate asOf) {
    if ("CUSTOM".equalsIgnoreCase(period) && customTo != null) {
      return customTo.isAfter(asOf) ? asOf : customTo;
    }
    return asOf;
  }

  /** Indian financial year: 1 April to 31 March. */
  public static LocalDate financialYearStart(LocalDate asOf) {
    int year = asOf.getMonthValue() >= 4 ? asOf.getYear() : asOf.getYear() - 1;
    return LocalDate.of(year, 4, 1);
  }

  public String periodLabel(LocalDate asOf) {
    return switch (period == null ? "LAST_30_DAYS" : period.toUpperCase()) {
      case "LAST_QUARTER" -> "Last quarter";
      case "YTD" -> "FY to date";
      case "CUSTOM" -> periodStart(asOf) + " to " + periodEnd(asOf);
      default -> "Last 30 days";
    };
  }

  /** True when the employee passes the grade, location and tenure filters. */
  public boolean matches(Employee e, LocalDate asOf) {
    if (!grades.isEmpty() && (e.grade() == null || !grades.contains(e.grade()))) {
      return false;
    }
    if (!locations.isEmpty() && (e.officeLocation() == null || !locations.contains(e.officeLocation()))) {
      return false;
    }
    if (tenureMinYears != null || tenureMaxYears != null) {
      Double tenure = tenureYears(e, asOf);
      if (tenure == null) {
        return false;
      }
      if (tenureMinYears != null && tenure < tenureMinYears) {
        return false;
      }
      if (tenureMaxYears != null && tenure > tenureMaxYears) {
        return false;
      }
    }
    return true;
  }

  /** Tenure to exit for leavers, to the as-of anchor for everyone else. */
  public static Double tenureYears(Employee e, LocalDate asOf) {
    if (e.dateOfJoining() == null) {
      return null;
    }
    LocalDate end = e.dateOfExit() != null && e.dateOfExit().isBefore(asOf) ? e.dateOfExit() : asOf;
    return ChronoUnit.DAYS.between(e.dateOfJoining(), end) / 365.25;
  }

  /**
   * Stable identity for a filter set, used to cache a generated narrative against the exact view it
   * describes so a narrative is never shown beside figures it was not written from.
   */
  public String cacheKey() {
    FilterSpec n = normalised();
    return String.join(
        "|",
        n.grades.stream().sorted().collect(Collectors.joining(",")),
        n.locations.stream().sorted().collect(Collectors.joining(",")),
        String.valueOf(n.tenureMinYears),
        String.valueOf(n.tenureMaxYears),
        n.period,
        String.valueOf(n.customFrom),
        String.valueOf(n.customTo));
  }

  public Set<String> gradeSet() {
    return Set.copyOf(grades);
  }
}
