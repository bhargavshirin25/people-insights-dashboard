package com.leadsquared.peopleinsights.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A retention action an HRBP logs against an at-risk employee. Visible to the logging HRBP and
 * to HR Head / CHRO.
 */
@Table("retention_actions")
public record RetentionAction(
    @Id String id,
    String employeeId,
    String vertical,
    /** Skip-level conversation | Compensation review | Role change | Manager change |
     *  Learning plan | Promotion nomination | Other */
    String actionType,
    LocalDate actionDate,
    String notes,
    String loggedByEmail,
    String loggedByName,
    Instant loggedAt) {}
