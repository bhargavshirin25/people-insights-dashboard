package com.leadsquared.peopleinsights.domain;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Exit Analysis Data (dataset 6) — NLP-processed exit interviews for the 1,476 leavers.
 *
 * <p>Ingested from the anonymised "Exit Analysis" sheet, which carries no employee name by
 * design. {@code verbatim} is surfaced to the UI with no name, id, or team reference attached.
 */
@Table("exit_records")
public record ExitRecord(
    @Id String employeeId,
    String grade,
    String vertical,
    String department,
    String designation,
    /** Voluntary - Regrettable | Voluntary - Non-Regrettable | Involuntary | Absconding */
    String exitType,
    LocalDate dateOfJoining,
    LocalDate dateOfExit,
    Double tenureMonths,
    String tenureBand,
    String primaryExitReason,
    @MappedCollection(idColumn = "exit_record", keyColumn = "exit_record_key") List<Theme> themes,
    Integer overallScore,
    String overallSentiment,
    String verbatim) {

  /** True when the exit counts toward voluntary attrition. */
  public boolean isVoluntary() {
    return exitType != null && exitType.startsWith("Voluntary");
  }

  public boolean isRegrettable() {
    return "Voluntary - Regrettable".equals(exitType);
  }

  @Table("exit_record_themes")
  public record Theme(String name, Integer score, String sentiment) {}
}
