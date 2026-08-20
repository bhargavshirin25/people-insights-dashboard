package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.AuditEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * The access trail.
 *
 * <p>The two ordered reads are written as explicit SQL rather than derived from the method name: a
 * `LIMIT` and an `ORDER BY` are the whole point of them, and spelling out the statement keeps that
 * visible instead of depending on how a name is parsed. Safe to do here because the table is flat —
 * a custom query does not populate child collections, so an aggregate with children must stay
 * derived.
 */
public interface AuditRepo extends ListCrudRepository<AuditEvent, String> {

  List<AuditEvent> findByUserEmailAndOutcomeAndAtAfter(
      String userEmail, String outcome, Instant after);

  /** The most recent 500 events, newest first — what the audit view lists. */
  @Query("SELECT * FROM audit_events ORDER BY at DESC LIMIT 500")
  List<AuditEvent> findTop500ByOrderByAtDesc();

  /** Everything since an instant, newest first — the 24-hour summary. */
  @Query("SELECT * FROM audit_events WHERE at > :after ORDER BY at DESC")
  List<AuditEvent> findSinceOrderByAtDesc(@Param("after") Instant after);
}
