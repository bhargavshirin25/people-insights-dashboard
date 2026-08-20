package com.leadsquared.peopleinsights.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Approved-but-unfilled headcount, backing the "Open approved headcount positions" metric card.
 *
 * <p>HR Ops supplied no requisition dataset in the Phase 1 extract, so this collection starts
 * empty and is maintained by an ADMIN until an ATS feed exists. The metric card renders a
 * "not configured" state rather than a fabricated number while it is empty.
 */
@Table("open_positions")
public record OpenPosition(
    @Id String id,
    String requisitionId,
    String vertical,
    String department,
    String designation,
    String grade,
    String location,
    int approvedCount,
    int filledCount,
    LocalDate approvedOn,
    LocalDate targetCloseDate,
    /** OPEN | ON_HOLD | CLOSED */
    String status,
    String updatedBy,
    Instant updatedAt) {

  public int vacantCount() {
    return Math.max(0, approvedCount - filledCount);
  }
}
