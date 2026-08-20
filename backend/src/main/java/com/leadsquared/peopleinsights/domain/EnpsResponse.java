package com.leadsquared.peopleinsights.domain;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * eNPS Survey Data (dataset 5).
 *
 * <p>{@code comments} are employee verbatims and must be anonymised in every shared or exported
 * view — see {@code EnpsService}, which never returns a comment alongside its employee id.
 */
@Table("enps_responses")
public record EnpsResponse(
    @Id String id,
    String employeeId,
    String fullName,
    String vertical,
    String grade,
    Integer npsScore,
    /** Promoter (9-10) | Passive (7-8) | Detractor (0-6) */
    String npsCategory,
    LocalDate surveyDate,
    /** Survey wave, e.g. "2026-06". Only one cycle exists in the HR Ops extract. */
    String cycle,
    String comments) {}
