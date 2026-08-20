package com.leadsquared.peopleinsights.domain;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Leave Records (dataset 3) — per-employee entitlement and utilisation for FY 2025-26. */
@Table("leave_balances")
public record LeaveBalance(
    @Id String employeeId,
    String fullName,
    String grade,
    String vertical,
    String department,
    String gender,
    String status,
    LocalDate dateOfJoining,
    Double elOpeningBalance,
    Double elAnnualEntitlement,
    Double elTotalAvailable,
    Double elTaken,
    Double elClosingBalance,
    Double elLapsed,
    Double elUtilisationPct,
    Double slAnnualEntitlement,
    Double slTaken,
    Double slBalance,
    Double slUtilisationPct,
    Double slLapsed,
    Double paternityEntitlement,
    Double paternityTaken,
    Double paternityBalance,
    Double maternityEntitlement,
    Double maternityTaken,
    Double maternityBalance,
    Double totalEntitlement,
    Double totalTaken,
    Double overallUtilisationPct) {}
