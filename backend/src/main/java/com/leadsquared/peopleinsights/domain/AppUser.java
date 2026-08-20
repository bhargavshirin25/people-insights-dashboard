package com.leadsquared.peopleinsights.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A dashboard user. Identity comes from the corporate IdP (Entra / Azure AD) in production; this
 * record holds the authorisation facts the IdP does not carry — role and BU assignment.
 *
 * <p>{@code assignedBus} is authoritative for HRBP scoping and is written only by an ADMIN.
 */
@Table("app_users")
public class AppUser implements Persistable<String> {

  @Id private String id = UUID.randomUUID().toString();
  private String email;

  private String displayName;
  private Role role;

  /** BUs this user may read. Meaningful for HRBP; ignored for org-wide roles. */
  /**
   * The user's business units, as the JSON array stored on the row.
   *
   * <p>Held as the string rather than as a parsed list beside it. The mapping layer populates fields
   * directly and does not call setters, so a parsed copy would silently stay empty on load — which
   * reads as "assigned to nothing" and locks an HRBP out of their own BU. One representation cannot
   * disagree with itself.
   */
  @Column("assigned_bus_json")
  @JsonIgnore
  private String assignedBusJson = StringLists.toJson(List.of());

  /**
   * False until this instance has been read back from the database.
   *
   * <p>Spring Data JDBC decides insert against update from {@link #isNew()}. Without this it would
   * see a non-null id on a brand-new user and issue an UPDATE that matches no row.
   */
  @Transient private boolean persisted;

  /** Set for dev-mode sign-in only; never populated for IdP-authenticated users. */
  private String devPasswordHash;

  private boolean enabled = true;
  private Instant createdAt = Instant.now();
  private Instant lastLoginAt;

  /** Persisted filter state, satisfying "filter state must persist across browser sessions". */
  private String savedFilterJson;

  public AppUser() {}

  public AppUser(String email, String displayName, Role role, List<String> assignedBus) {
    this.email = email;
    this.displayName = displayName;
    this.role = role;
    this.assignedBusJson = StringLists.toJson(assignedBus == null ? List.of() : assignedBus);
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public Role getRole() {
    return role;
  }

  public void setRole(Role role) {
    this.role = role;
  }

  public List<String> getAssignedBus() {
    return StringLists.fromJson(assignedBusJson);
  }

  public void setAssignedBus(List<String> assignedBus) {
    this.assignedBusJson = StringLists.toJson(assignedBus == null ? List.of() : assignedBus);
  }

  public String getAssignedBusJson() {
    return assignedBusJson;
  }

  public void setAssignedBusJson(String assignedBusJson) {
    this.assignedBusJson = assignedBusJson;
  }

  @Override
  @JsonIgnore
  public boolean isNew() {
    return !persisted;
  }

  /** Marks this instance as loaded, so the next save updates rather than inserts. */
  public void markPersisted() {
    this.persisted = true;
  }

  public String getDevPasswordHash() {
    return devPasswordHash;
  }

  public void setDevPasswordHash(String devPasswordHash) {
    this.devPasswordHash = devPasswordHash;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  public void setLastLoginAt(Instant lastLoginAt) {
    this.lastLoginAt = lastLoginAt;
  }

  public String getSavedFilterJson() {
    return savedFilterJson;
  }

  public void setSavedFilterJson(String savedFilterJson) {
    this.savedFilterJson = savedFilterJson;
  }
}
