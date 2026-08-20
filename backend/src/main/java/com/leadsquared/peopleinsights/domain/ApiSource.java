package com.leadsquared.peopleinsights.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A GET endpoint the application polls on an interval, and the record of how that last went.
 *
 * <p>Read-only by construction: the method is not configurable, so a source can pull data in and can
 * never push anything out. The rest — where the records sit inside the response, how often to look, and
 * whether each run replaces the last — is configuration rather than code, which is the point: a new feed
 * is a form on the data-source screen instead of a class and a migration.
 */
@Table("api_sources")
public class ApiSource implements Persistable<String> {

  @Id private String id = UUID.randomUUID().toString();

  private String name;
  private String url;

  /** JSON object of request headers, for an API key or an Accept override. */
  @Column("headers_json")
  @JsonIgnore
  private String headersJson;

  /** Dotted path to the array inside the response; blank for the root. */
  private String jsonPath;

  /** Field inside each record to keep as a readable key. The whole record is stored either way. */
  private String keyField;

  private int intervalSeconds = 300;
  private boolean enabled = true;

  /** Clear this source's previous rows before writing the new ones. Off appends instead. */
  private boolean replaceEachRun = true;

  private Instant lastRunAt;

  /** OK | FAILED | null before the first run. */
  private String lastStatus;

  private String lastMessage;
  private Integer lastRowCount;

  private String createdBy;
  private Instant createdAt = Instant.now();
  private String updatedBy;
  private Instant updatedAt = Instant.now();

  @Transient private boolean persisted;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getHeadersJson() {
    return headersJson;
  }

  public void setHeadersJson(String headersJson) {
    this.headersJson = headersJson;
  }

  public String getJsonPath() {
    return jsonPath;
  }

  public void setJsonPath(String jsonPath) {
    this.jsonPath = jsonPath;
  }

  public String getKeyField() {
    return keyField;
  }

  public void setKeyField(String keyField) {
    this.keyField = keyField;
  }

  public int getIntervalSeconds() {
    return intervalSeconds;
  }

  public void setIntervalSeconds(int intervalSeconds) {
    this.intervalSeconds = intervalSeconds;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isReplaceEachRun() {
    return replaceEachRun;
  }

  public void setReplaceEachRun(boolean replaceEachRun) {
    this.replaceEachRun = replaceEachRun;
  }

  public Instant getLastRunAt() {
    return lastRunAt;
  }

  public void setLastRunAt(Instant lastRunAt) {
    this.lastRunAt = lastRunAt;
  }

  public String getLastStatus() {
    return lastStatus;
  }

  public void setLastStatus(String lastStatus) {
    this.lastStatus = lastStatus;
  }

  public String getLastMessage() {
    return lastMessage;
  }

  public void setLastMessage(String lastMessage) {
    this.lastMessage = lastMessage;
  }

  public Integer getLastRowCount() {
    return lastRowCount;
  }

  public void setLastRowCount(Integer lastRowCount) {
    this.lastRowCount = lastRowCount;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  /**
   * Whether this source is due, given the clock.
   *
   * <p>Measured from the end of the last run rather than from a fixed schedule, so a slow endpoint
   * cannot queue up overlapping runs — the next one starts an interval after the last one finished.
   */
  public boolean isDue(Instant now) {
    if (!enabled) {
      return false;
    }
    if (lastRunAt == null) {
      return true;
    }
    return !now.isBefore(lastRunAt.plusSeconds(Math.max(1, intervalSeconds)));
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
