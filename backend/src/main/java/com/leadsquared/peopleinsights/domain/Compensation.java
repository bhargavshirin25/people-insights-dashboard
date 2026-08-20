package com.leadsquared.peopleinsights.domain;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Compensation Data (dataset 2). The most sensitive collection in the system: individual rows
 * never leave the API except to an HRBP scoped to the employee's own BU, or to HR Head / CHRO.
 * Everything below that tier reads grade-band aggregates only.
 */
@Table("compensation")
public record Compensation(
    @Id String employeeId,
    String fullName,
    String grade,
    String vertical,
    String designation,
    String location,
    String employeeType,
    Double annualFixedCtc,
    Double annualVariableTarget,
    Double variablePctOfCtc,
    Double totalTargetCtc,
    Double monthlyCtc,
    Double monthlyBasic,
    Double monthlyHra,
    Double specialAllowance,
    Double employerPf,
    Double gratuityProvision,
    Double monthlyGross,
    Double employeePfDeduction,
    Double professionalTax,
    Double estMonthlyInHand,
    Double gradeBandMin,
    Double gradeBandMidpoint,
    Double gradeBandMax,
    Double compaRatio,
    String bandPosition,
    Double lastIncrementPct,
    LocalDate lastIncrementDate) {}
