package com.leadsquared.peopleinsights.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Mandatory audit trail. One document per data-access event: who, which BU, which data type,
 * when. Retained 90 days by the audit_events_retention scheduled event (see schema.sql) and
 * readable by HR Ops
 * admin and HR Tech only.
 *
 * <p>{@code outcome} distinguishes granted reads from denied cross-BU attempts, which is what
 * makes anomalous-access alerting possible.
 */
@Table("audit_events")
public record AuditEvent(
    @Id String id,
    Instant at,
    String userEmail,
    Role role,
    /** BU the request targeted, or "ALL" for an org-wide read. */
    String businessUnit,
    /** HEADCOUNT | ATTRITION | COMPENSATION | PERFORMANCE | ENPS | EXIT | LEAVE | ATTENDANCE
     *  | RISK_REGISTER | CALENDAR | NARRATIVE | EXPORT | CONFIG */
    String dataType,
    String action,
    /** GRANTED | DENIED */
    String outcome,
    String detail,
    String requestPath,
    String sourceIp) {

  public static final String GRANTED = "GRANTED";
  public static final String DENIED = "DENIED";
}
