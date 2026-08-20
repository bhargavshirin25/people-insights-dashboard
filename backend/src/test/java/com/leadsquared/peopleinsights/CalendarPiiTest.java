package com.leadsquared.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.metrics.CalendarService;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.FilterSpec;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The HR calendar withholds individual events from roles that may not see individual data.
 *
 * <p>Every calendar event names one person, so the event list is individual-grain data even though it
 * sits on an otherwise aggregate view. A Viewer receiving 285 named promotion and probation events is
 * the failure this guards against.
 */
class CalendarPiiTest {

  private static final LocalDate AS_OF = LocalDate.of(2026, 7, 31);

  private final CalendarService calendar = new CalendarService();

  /** One employee with a work anniversary inside the 30-day window. */
  private Dataset dataset() {
    Employee e =
        new Employee(
            "LS04424",
            "Foram",
            "Garg",
            "Foram Garg",
            "Some HRBP",
            AS_OF.minusYears(3).plusDays(5), // three-year anniversary inside the window
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

  @Test
  @DisplayName("a role with individual rights receives the named events")
  void hrbpSeesNames() {
    var view = calendar.build(dataset(), true);

    assertThat(view.eventsVisible()).isTrue();
    assertThat(view.events()).isNotEmpty();
    assertThat(view.events().get(0).employeeName()).isEqualTo("Foram Garg");
    assertThat(view.totalEvents()).isEqualTo(view.events().size());
    assertThat(view.eventsNote()).isNull();
  }

  @Test
  @DisplayName("a role without individual rights receives counts and no rows")
  void viewerSeesNoIndividuals() {
    var withNames = calendar.build(dataset(), true);
    var view = calendar.build(dataset(), false);

    assertThat(view.eventsVisible()).isFalse();
    assertThat(view.events()).isEmpty();
    assertThat(view.eventsNote()).isNotBlank();

    // The counts stay true rather than collapsing to zero with the rows.
    assertThat(view.totalEvents()).isEqualTo(withNames.totalEvents()).isPositive();
    assertThat(view.typeCounts()).isEqualTo(withNames.typeCounts());
    assertThat(view.typeLabels()).isEqualTo(withNames.typeLabels());
  }

  @Test
  @DisplayName("no name or employee id survives anywhere in an aggregate-only view")
  void aggregateViewCarriesNoIdentifiers() {
    var view = calendar.build(dataset(), false);

    assertThat(view.toString()).doesNotContain("Foram").doesNotContain("LS04424");
  }
}
