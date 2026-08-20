package com.leadsquared.peopleinsights.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Record of one ingestion of the HR Ops workbooks, so data freshness is auditable.
 *
 * <p>The per-table counts and the warning list are stored as JSON in one column each. Both are read
 * only as a whole, by the admin data-status panel, so a child table would add a join without adding a
 * query anyone makes. {@link #counts()} and {@link #warnings()} present them as the map and list the
 * rest of the application expects, and carry the JSON names the API already published.
 */
@Table("ingest_runs")
public record IngestRun(
    @Id String id,
    Instant startedAt,
    Instant finishedAt,
    String sourceDirectory,
    @Column("counts_json") @JsonIgnore String countsJson,
    @Column("warnings_json") @JsonIgnore String warningsJson,
    /** Latest attendance month present in the data — the dashboard's "as of" anchor. */
    String dataAsOfDate,
    String status) {

  /** Builds a run from the rich types the ingest and migration paths hold. */
  public static IngestRun of(
      String id,
      Instant startedAt,
      Instant finishedAt,
      String sourceDirectory,
      Map<String, Integer> counts,
      List<String> warnings,
      String dataAsOfDate,
      String status) {
    return new IngestRun(
        id,
        startedAt,
        finishedAt,
        sourceDirectory,
        StringLists.countsToJson(counts),
        StringLists.toJson(warnings),
        dataAsOfDate,
        status);
  }

  /** Table name -> rows written. */
  @JsonProperty("counts")
  public Map<String, Integer> counts() {
    return StringLists.countsFromJson(countsJson);
  }

  @JsonProperty("warnings")
  public List<String> warnings() {
    return StringLists.fromJson(warningsJson);
  }
}
