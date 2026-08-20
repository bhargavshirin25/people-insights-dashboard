package com.leadsquared.peopleinsights.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A role defined by an administrator: a name, a set of permissions, a business-unit scope, and the
 * email addresses it applies to.
 *
 * <p>Membership is held on the role rather than on the user record, and that is deliberate. A user row
 * exists only once someone has signed in or been provisioned, but access has to be grantable to a
 * colleague who has not arrived yet — so the role names the address, and the address is matched at
 * sign-in. It also means one address can hold several roles, whose permissions and business units are
 * unioned.
 *
 * <p>The three JSON columns follow {@link AppUser}'s pattern for the same reason: the mapping layer
 * populates fields directly without calling setters, so a parsed copy kept alongside the string would
 * load empty and read as "no permissions" — which for this table means locking someone out.
 */
@Table("custom_roles")
public class CustomRole implements Persistable<String> {

  @Id private String id = UUID.randomUUID().toString();

  private String name;
  private String description;

  @Column("permissions_json")
  @JsonIgnore
  private String permissionsJson = StringLists.toJson(List.of());

  /** Business units in scope. Ignored when {@link #allBusinessUnits} is set. */
  @Column("business_units_json")
  @JsonIgnore
  private String businessUnitsJson = StringLists.toJson(List.of());

  /**
   * Every business unit, including ones added later.
   *
   * <p>Distinct from listing all eight: a role marked this way follows {@code dashboard.business-units}
   * as it changes, where an enumerated list silently excludes a ninth unit when the company adds one.
   */
  private boolean allBusinessUnits;

  @Column("member_emails_json")
  @JsonIgnore
  private String memberEmailsJson = StringLists.toJson(List.of());

  /**
   * A role the application depends on and an administrator may not delete.
   *
   * <p>There is exactly one: the seeded full-access role. Without it, revoking the last account holding
   * {@code MANAGE_ACCESS} would leave nobody able to grant it back, and the only way in would be a SQL
   * client.
   */
  private boolean systemRole;

  private String createdBy;
  private Instant createdAt = Instant.now();
  private String updatedBy;
  private Instant updatedAt = Instant.now();

  @Transient private boolean persisted;

  public CustomRole() {}

  public CustomRole(String name, String description, List<Permission> permissions) {
    this.name = name;
    this.description = description;
    setPermissions(permissions);
  }

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

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  /** Stored keys resolved to permissions. An unrecognised key is dropped, not fatal. */
  public List<Permission> getPermissions() {
    return StringLists.fromJson(permissionsJson).stream()
        .map(Permission::of)
        .flatMap(java.util.Optional::stream)
        .toList();
  }

  public void setPermissions(List<Permission> permissions) {
    this.permissionsJson =
        StringLists.toJson(
            permissions == null ? List.of() : permissions.stream().map(Enum::name).distinct().toList());
  }

  public String getPermissionsJson() {
    return permissionsJson;
  }

  public void setPermissionsJson(String permissionsJson) {
    this.permissionsJson = permissionsJson;
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

  public boolean isAllBusinessUnits() {
    return allBusinessUnits;
  }

  public void setAllBusinessUnits(boolean allBusinessUnits) {
    this.allBusinessUnits = allBusinessUnits;
  }

  public List<String> getMemberEmails() {
    return StringLists.fromJson(memberEmailsJson);
  }

  public void setMemberEmails(List<String> memberEmails) {
    this.memberEmailsJson =
        StringLists.toJson(
            memberEmails == null
                ? List.of()
                : memberEmails.stream()
                    .filter(e -> e != null && !e.isBlank())
                    .map(e -> e.trim().toLowerCase())
                    .distinct()
                    .toList());
  }

  public String getMemberEmailsJson() {
    return memberEmailsJson;
  }

  public void setMemberEmailsJson(String memberEmailsJson) {
    this.memberEmailsJson = memberEmailsJson;
  }

  public boolean isSystemRole() {
    return systemRole;
  }

  public void setSystemRole(boolean systemRole) {
    this.systemRole = systemRole;
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

  /** True when this role applies to the given address, matched case-insensitively. */
  public boolean covers(String email) {
    if (email == null) {
      return false;
    }
    String wanted = email.trim();
    return getMemberEmails().stream().anyMatch(m -> m.equalsIgnoreCase(wanted));
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
