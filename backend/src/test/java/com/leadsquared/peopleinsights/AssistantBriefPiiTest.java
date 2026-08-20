package com.leadsquared.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.leadsquared.peopleinsights.ai.DashboardBrief;
import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.domain.Permission;
import com.leadsquared.peopleinsights.domain.Role;
import com.leadsquared.peopleinsights.metrics.CalendarService;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.ExitAnalyticsService;
import com.leadsquared.peopleinsights.metrics.FilterSpec;
import com.leadsquared.peopleinsights.metrics.LeaveAttendanceService;
import com.leadsquared.peopleinsights.metrics.MetricsService;
import com.leadsquared.peopleinsights.metrics.PerformanceService;
import com.leadsquared.peopleinsights.metrics.RiskScoringService;
import com.leadsquared.peopleinsights.repo.OpenPositionRepo;
import com.leadsquared.peopleinsights.security.AccessScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The assistant's brief obeys the same PII gate the views do.
 *
 * <p>This is the property that matters most about the assistant: it answers from a brief, so anything
 * the brief contains can be repeated back in a reply. A Viewer's brief must therefore hold no name and
 * no employee id at all, rather than holding them and relying on an instruction not to say them.
 */
class AssistantBriefPiiTest {

  private static final LocalDate AS_OF = LocalDate.of(2026, 7, 31);
  private static final String NAME = "Foram Garg";
  private static final String EMPLOYEE_ID = "LS04424";

  private final RiskScoringService risk = new RiskScoringService();

  private final DashboardBrief brief =
      new DashboardBrief(
          new MetricsService(risk, mock(OpenPositionRepo.class)),
          risk,
          new ExitAnalyticsService(),
          new PerformanceService(risk),
          new LeaveAttendanceService(risk),
          new CalendarService());

  /** One active employee with a work anniversary inside the calendar window. */
  private Dataset dataset() {
    Employee e =
        new Employee(
            EMPLOYEE_ID,
            "Foram",
            "Garg",
            NAME,
            "Some HRBP",
            AS_OF.minusYears(3).plusDays(5),
            "foram.garg@leadsquared.com",
            "Active",
            "Product Manager",
            "M3",
            "Product",
            "Product > Product Management",
            "Product Management",
            null,
            null,
            null,
            "A Manager",
            "LS00001",
            null,
            null,
            null,
            null,
            null,
            "Full Time",
            null,
            "Bengaluru",
            "Bengaluru",
            null,
            null,
            null,
            null,
            null,
            List.of());
    return new Dataset(
        List.of("Product"),
        "Product",
        AS_OF,
        FilterSpec.none(),
        List.of(e),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        List.of());
  }

  /** A scope over Product, with or without the individual-data permission. */
  private static AccessScope scope(boolean individualData) {
    var permissions =
        individualData
            ? java.util.Set.of(Permission.VIEW_OVERVIEW, Permission.SEE_INDIVIDUAL_PII)
            : java.util.Set.of(Permission.VIEW_OVERVIEW);
    return AccessScope.of(
        "reader@leadsquared.com", "A Reader", Role.HRBP, List.of("Product"), permissions, false);
  }

  @Test
  @DisplayName("a brief for a role with individual rights names individuals")
  void briefCarriesNamesWhenGranted() {
    String text = brief.build(dataset(), scope(true));

    assertThat(text).contains(NAME).contains(EMPLOYEE_ID);
    assertThat(text).contains("Individual-level data available to this reader: yes");
  }

  @Test
  @DisplayName("a brief for a role without individual rights carries no name and no employee id at all")
  void briefIsAnonymousWhenNotGranted() {
    String text = brief.build(dataset(), scope(false));

    assertThat(text).doesNotContain(NAME).doesNotContain(EMPLOYEE_ID).doesNotContain("Foram");
    assertThat(text).contains("Individual-level data available to this reader: NO");
    assertThat(text).contains("withheld");
  }

  @Test
  @DisplayName("the brief states the reporting date and the scope it was built for")
  void briefStatesItsOwnScope() {
    String text = brief.build(dataset(), scope(true));

    assertThat(text).contains("Reporting date: 2026-07-31");
    assertThat(text).contains("Business units this reader may see: Product");
    // Nothing outside the granted scope can be described, because nothing else is in the dataset.
    assertThat(text).doesNotContain("Engineering").doesNotContain("Sales");
  }
}
