package com.leadsquared.peopleinsights.security;

import com.leadsquared.peopleinsights.domain.Permission;
import com.leadsquared.peopleinsights.domain.Role;
import java.util.List;
import java.util.Set;

/**
 * What one authenticated user is allowed to read, fully resolved.
 *
 * <p>{@code allowedBus} is always a concrete list — org-wide roles and custom roles marked for every
 * unit get each BU expanded rather than a wildcard, so a query predicate can be built the same way for
 * every role and no code path can "forget" to constrain a BU.
 *
 * <p>{@code permissions} is the resolved set from {@link AccessPolicy} — the union of the roles naming
 * this address, and empty when none do. The capability questions below are answered from it rather than
 * from {@link #role()}, which is a label only: every call site asking "may they see names?" therefore gets
 * the answer the granted role gave, not one inferred from a tier.
 */
public record AccessScope(
    String email,
    String displayName,
    Role role,
    List<String> allowedBus,
    Set<Permission> permissions,
    boolean orgWide) {

  /** A scope with permissions stated outright — used by tests and by callers building one by hand. */
  public static AccessScope of(
      String email,
      String displayName,
      Role role,
      List<String> allowedBus,
      Set<Permission> permissions,
      boolean orgWide) {
    return new AccessScope(
        email,
        displayName,
        role,
        allowedBus == null ? List.of() : List.copyOf(allowedBus),
        permissions == null ? Set.of() : Set.copyOf(permissions),
        orgWide);
  }

  public boolean allowsBu(String bu) {
    return bu != null && allowedBus.stream().anyMatch(b -> b.equalsIgnoreCase(bu));
  }

  public boolean has(Permission permission) {
    return permissions != null && permissions.contains(permission);
  }

  /** Names, employee ids, and individual-grain rows. */
  public boolean canSeeIndividualPii() {
    return has(Permission.SEE_INDIVIDUAL_PII);
  }

  /** Individual compensation. Checked separately from the rest of PII so it can be withheld alone. */
  public boolean canSeeIndividualCompensation() {
    return has(Permission.SEE_COMPENSATION);
  }

  public boolean isOrgWide() {
    return orgWide;
  }

  /** True when this scope may read employee data at all, in any view. */
  public boolean canReadEmployeeData() {
    return Permission.dataViews().stream().anyMatch(this::has);
  }
}
