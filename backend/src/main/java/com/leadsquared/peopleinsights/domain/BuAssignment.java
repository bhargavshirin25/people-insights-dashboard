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
 * BU-to-HRBP mapping — an ADMIN-only configuration artefact.
 *
 * <p>This exists as its own collection rather than being derived from the master data's
 * "HRBP Name" column because that column is a relationship field, not an assignment: in the HR
 * Ops extract every one of the ten named HRBPs has employees in all eight BUs. Deriving scope
 * from it would grant every HRBP org-wide read access, which the access-control rules forbid.
 */
@Table("bu_assignments")
public class BuAssignment implements Persistable<String> {

  @Id private String id = UUID.randomUUID().toString();
  private String hrbpEmail;

  private String hrbpName;
  /** The assigned business units as stored JSON — see {@link AppUser#getAssignedBus()}. */
  @Column("business_units_json")
  @JsonIgnore
  private String businessUnitsJson = StringLists.toJson(List.of());

  /** See {@link AppUser#isNew()} — insert against update is decided from this, not from the id. */
  @Transient private boolean persisted;
  private String updatedBy;
  private Instant updatedAt = Instant.now();

  public BuAssignment() {}

  public BuAssignment(String hrbpEmail, String hrbpName, List<String> businessUnits, String updatedBy) {
    this.hrbpEmail = hrbpEmail;
    this.hrbpName = hrbpName;
    this.businessUnitsJson = StringLists.toJson(businessUnits == null ? List.of() : businessUnits);
    this.updatedBy = updatedBy;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getHrbpEmail() {
    return hrbpEmail;
  }

  public void setHrbpEmail(String hrbpEmail) {
    this.hrbpEmail = hrbpEmail;
  }

  public String getHrbpName() {
    return hrbpName;
  }

  public void setHrbpName(String hrbpName) {
    this.hrbpName = hrbpName;
  }

  public List<String> getBusinessUnits() {
    return StringLists.fromJson(businessUnitsJson);
  }

  public void setBusinessUnits(List<String> businessUnits) {
    this.businessUnitsJson = StringLists.toJson(businessUnits == null ? List.of() : businessUnits);
  }

  public String getBusinessUnitsJson() {
    return businessUnitsJson;
  }

  public void setBusinessUnitsJson(String businessUnitsJson) {
    this.businessUnitsJson = businessUnitsJson;
  }

  @Override
  @JsonIgnore
  public boolean isNew() {
    return !persisted;
  }

  public void markPersisted() {
    this.persisted = true;
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
}
