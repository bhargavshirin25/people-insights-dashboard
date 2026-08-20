package com.leadsquared.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadsquared.peopleinsights.ai.NarrativeService;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The traceability rule: every figure in a narrative must come from the metric cards on the same view.
 *
 * <p>These test the verification step rather than the prompt, because a prompt instruction is not a
 * guarantee. A narrative citing a number that appears nowhere in the fact sheet has to be caught.
 */
class NarrativeVerificationTest {

  private static final String FACTS =
      """
      FACTS
      Business unit: Engineering
      Reporting date: 2026-07-31
      Employees in scope: 612

      METRIC CARDS ON THIS VIEW
      - Current headcount: 431 (month-on-month -4.0, -0.9%, direction down)
      - Voluntary attrition (rolling 3-month): 18.4% (month-on-month +2.1, +12.9%, direction up)
      - eNPS score (2026-06): +3
      - At-risk employees: 57 (month-on-month +5.0, +9.6%, direction up)
      """;

  @Test
  @DisplayName("figures taken from the fact sheet pass verification")
  void allowsFiguresFromFacts() {
    Set<String> allowed = NarrativeService.allowedNumbers(FACTS);

    String narrative =
        "Engineering headcount stands at 431, down 0.9% month-on-month, while rolling 3-month "
            + "voluntary attrition rose to 18.4%. The at-risk register now holds 57 employees.";

    assertThat(NarrativeService.unverifiableNumbers(narrative, allowed)).isEmpty();
  }

  @Test
  @DisplayName("an invented figure is caught")
  void rejectsInventedFigure() {
    Set<String> allowed = NarrativeService.allowedNumbers(FACTS);

    // 23.7 appears nowhere in the facts — the classic plausible-but-fabricated number.
    String narrative = "Engineering attrition has reached 23.7%, the highest of any business unit.";

    assertThat(NarrativeService.unverifiableNumbers(narrative, allowed)).containsExactly("23.7");
  }

  @Test
  @DisplayName("a rounded restatement of a real figure is accepted")
  void allowsRounding() {
    Set<String> allowed = NarrativeService.allowedNumbers(FACTS);

    // 18% is a rounding of the real 18.4%, which is a legitimate way to write it in prose.
    assertThat(NarrativeService.unverifiableNumbers("Attrition is about 18%.", allowed)).isEmpty();
  }

  @Test
  @DisplayName("thousands separators and signs do not create false positives")
  void normalisesFormatting() {
    Set<String> allowed = NarrativeService.allowedNumbers("FACTS\nHeadcount: 1,204\nChange: -12.0");

    assertThat(NarrativeService.unverifiableNumbers("Headcount is 1204, down 12.", allowed)).isEmpty();
  }

  @Test
  @DisplayName("prose with no figures always verifies")
  void allowsQualitativeProse() {
    Set<String> allowed = NarrativeService.allowedNumbers(FACTS);

    assertThat(
            NarrativeService.unverifiableNumbers(
                "Attrition is rising and warrants attention from the HRBP.", allowed))
        .isEmpty();
  }
}
