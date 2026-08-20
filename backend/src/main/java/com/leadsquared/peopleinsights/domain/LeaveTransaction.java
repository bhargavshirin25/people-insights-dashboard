package com.leadsquared.peopleinsights.domain;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Individual leave transactions for FY 2025-26 (dataset 3, transaction grain). */
@Table("leave_transactions")
public record LeaveTransaction(
    @Id String transactionId,
    String employeeId,
    String employeeName,
    String grade,
    String vertical,
    /** Earned Leave | Sick Leave | Paternity Leave | Maternity Leave */
    String leaveType,
    LocalDate fromDate,
    LocalDate toDate,
    Double days,
    LocalDate appliedOn,
    String reason,
    /** Approved | Cancelled | Pending | Rejected */
    String status) {

  /** Applied on or after the leave start date — the signal for unplanned absence. */
  public boolean isUnplanned() {
    return appliedOn != null && fromDate != null && !appliedOn.isBefore(fromDate);
  }
}
