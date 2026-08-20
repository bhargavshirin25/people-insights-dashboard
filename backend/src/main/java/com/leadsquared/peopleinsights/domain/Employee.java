package com.leadsquared.peopleinsights.domain;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Employee Master Data (dataset 1) plus the PMS/appraisal columns (dataset 7), which HR Ops
 * ships inside the same sheet rather than as a separate file.
 *
 * <p>{@code vertical} is the Business Unit and is the axis every access-control decision turns
 * on, so it is indexed and never null for an ingested row.
 */
@Table("employees")
public record Employee(
    @Id String employeeId,
    String firstName,
    String lastName,
    String fullName,
    /** Relationship field from the source data. NOT an access-control assignment: every HRBP
     *  name in the source spans all eight BUs. Real scoping lives in {@link BuAssignment}. */
    String hrbpName,
    LocalDate dateOfJoining,
    String email,
    /** "Active" | "Inactive" */
    String status,
    String designation,
    String grade,
    /** Business Unit. */
    String vertical,
    String departmentHierarchy,
    /** Leaf of the hierarchy, e.g. "Legal & Compliance" from "Operations > Legal & Compliance". */
    String department,
    String subDivision,
    String costCenter,
    String costCenterId,
    String managerName,
    String managerId,
    String l2Manager,
    String functionHeadName,
    String functionHeadId,
    String buHeadName,
    String buHeadId,
    /** "Regular" | "Contractor" | "Intern" */
    String employeeType,
    LocalDate dateOfExit,
    String officeLocation,
    String baseOfficeLocation,
    LocalDate probationStartDate,
    Integer probationPeriodDays,
    LocalDate probationEndDate,
    LocalDate confirmationDate,
    /** Voluntary - Regrettable | Voluntary - Non-Regrettable | Involuntary | Absconding */
    String exitType,
    /** Appraisal cycles, oldest first. Child table; the list index is what preserves that order. */
    @MappedCollection(idColumn = "employee", keyColumn = "employee_key") List<PmsCycle> pms) {

  public boolean isActive() {
    return "Active".equalsIgnoreCase(status);
  }

  /** Rating for a cycle label such as "FY2025-26", or null when not appraised in that cycle. */
  public Integer ratingFor(String cycle) {
    if (pms == null) {
      return null;
    }
    return pms.stream()
        .filter(c -> cycle.equals(c.cycle()))
        .map(PmsCycle::rating)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /** Ratings in cycle order with gaps removed — used for trend and consistency checks. */
  public List<Integer> ratingTrend() {
    if (pms == null) {
      return List.of();
    }
    return pms.stream().map(PmsCycle::rating).filter(java.util.Objects::nonNull).toList();
  }

  @Table("employee_pms_cycles")
  public record PmsCycle(
      String cycle,
      Integer rating,
      LocalDate appraisalDate,
      boolean promoted,
      String prePromotionDesignation,
      String postPromotionDesignation,
      LocalDate promotionDate) {}
}
