package com.leadsquared.peopleinsights.domain;

/**
 * The access tiers. Ordering matters: {@link #atLeast} implements the "HRBP level and above"
 * rule that governs PII, individual rows, and compensation.
 *
 * <p>ADMIN is deliberately not "above" HRBP for data purposes — it is a configuration role that
 * must not see employee PII. It therefore sits at rank 0 and is handled by an explicit check
 * rather than by rank comparison.
 */
public enum Role {
  /** Below HRBP: aggregated, anonymised metrics only. */
  VIEWER(1),
  /** Scoped to assigned BUs; sees individual rows within them. */
  HRBP(2),
  /** All BUs, all HRBP action logs and at-risk registers. Cannot change BU->HRBP mapping. */
  HR_HEAD(3),
  /** Org-wide, same data rights as HR Head plus the org heat map. */
  CHRO(4),
  /** Configuration only. No employee PII. */
  ADMIN(0);

  private final int rank;

  Role(int rank) {
    this.rank = rank;
  }

  public int rank() {
    return rank;
  }

  /** True when this role is at or above {@code other} in the data-access hierarchy. */
  public boolean atLeast(Role other) {
    return this.rank >= other.rank;
  }

  /** True when the role may see names, employee ids, and individual-grain data. */
  public boolean canSeeIndividualPii() {
    return this == HRBP || this == HR_HEAD || this == CHRO;
  }

  /** True when the role may see any BU without an explicit assignment. */
  public boolean isOrgWide() {
    return this == HR_HEAD || this == CHRO;
  }

  public String authority() {
    return "ROLE_" + name();
  }
}
