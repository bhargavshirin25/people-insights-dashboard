package com.leadsquared.peopleinsights.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A stored AI narrative summary for one BU + filter combination.
 *
 * <p>{@code citedFigures} is the traceability record: every number the model was allowed to use,
 * captured from the metric cards on the same view. {@code editedBy} being non-null is what drives
 * the "Modified by [user]" tag in the UI.
 */
@Table("narratives")
public class NarrativeDoc implements Persistable<String> {

  @Id private String id = UUID.randomUUID().toString();

  private String businessUnit;

  /** Hash of the active filter set, so a narrative is not reused across different filters. */
  private String filterKey;

  /**
   * Fingerprint of the metric figures this narrative was written from.
   *
   * <p>A stored narrative is only reused while this still matches the current figures. That is what
   * makes "refreshes with each data update" true in practice: if a re-ingest, a filter, or a change to
   * how a metric is computed moves any number, the fingerprint changes and the narrative is rewritten
   * rather than left describing figures that are no longer on screen.
   */
  private String factsHash;

  private String text;
  @MappedCollection(idColumn = "narrative_doc", keyColumn = "narrative_doc_key")
  private List<Anomaly> anomalies = List.of();
  @MappedCollection(idColumn = "narrative_doc", keyColumn = "narrative_doc_key")
  private List<CitedFigure> citedFigures = List.of();
  private String model;
  private Instant generatedAt = Instant.now();
  private String generatedForUser;

  private String editedBy;
  private String editedByName;
  private Instant editedAt;

  /** True when the model was unavailable and the text is a deterministic fallback. */
  private boolean fallback;

  /** See {@link AppUser#isNew()}. An edited narrative is loaded first, so it updates in place. */
  @Transient private boolean persisted;

  @Table("narrative_anomalies")
  public record Anomaly(String metric, String severity, String description) {}

  @Table("narrative_cited_figures")
  public record CitedFigure(String label, String value) {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getBusinessUnit() {
    return businessUnit;
  }

  public void setBusinessUnit(String businessUnit) {
    this.businessUnit = businessUnit;
  }

  public String getFilterKey() {
    return filterKey;
  }

  public void setFilterKey(String filterKey) {
    this.filterKey = filterKey;
  }

  public String getFactsHash() {
    return factsHash;
  }

  public void setFactsHash(String factsHash) {
    this.factsHash = factsHash;
  }

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }

  public List<Anomaly> getAnomalies() {
    return anomalies == null ? List.of() : anomalies;
  }

  public void setAnomalies(List<Anomaly> anomalies) {
    this.anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
  }

  public List<CitedFigure> getCitedFigures() {
    return citedFigures == null ? List.of() : citedFigures;
  }

  public void setCitedFigures(List<CitedFigure> citedFigures) {
    this.citedFigures = citedFigures == null ? List.of() : List.copyOf(citedFigures);
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public Instant getGeneratedAt() {
    return generatedAt;
  }

  public void setGeneratedAt(Instant generatedAt) {
    this.generatedAt = generatedAt;
  }

  public String getGeneratedForUser() {
    return generatedForUser;
  }

  public void setGeneratedForUser(String generatedForUser) {
    this.generatedForUser = generatedForUser;
  }

  public String getEditedBy() {
    return editedBy;
  }

  public void setEditedBy(String editedBy) {
    this.editedBy = editedBy;
  }

  public String getEditedByName() {
    return editedByName;
  }

  public void setEditedByName(String editedByName) {
    this.editedByName = editedByName;
  }

  public Instant getEditedAt() {
    return editedAt;
  }

  public void setEditedAt(Instant editedAt) {
    this.editedAt = editedAt;
  }

  public boolean isFallback() {
    return fallback;
  }

  public void setFallback(boolean fallback) {
    this.fallback = fallback;
  }

  @Override
  @JsonIgnore
  public boolean isNew() {
    return !persisted;
  }

  public void markPersisted() {
    this.persisted = true;
  }
}
