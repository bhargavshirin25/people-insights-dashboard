package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.AuditEvent;
import com.leadsquared.peopleinsights.security.AuditService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail, readable by HR Ops admin and HR Tech plus the org-wide HR roles (enforced in the
 * filter chain).
 *
 * <p>The anomaly endpoint is the operational half of the requirement: retention alone is a record, and
 * what makes it a control is being able to see that one user is repeatedly probing BUs they are not
 * assigned to.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

  private final AuditService audit;
  private final AppProperties props;

  public AuditController(AuditService audit, AppProperties props) {
    this.audit = audit;
    this.props = props;
  }

  @GetMapping("/recent")
  public Map<String, Object> recent() {
    List<AuditEvent> events = audit.recent();
    return Map.of(
        "retentionDays", props.getAuditRetentionDays(),
        "count", events.size(),
        "events", events);
  }

  public record AnomalyReport(
      String userEmail, String role, long deniedAttempts, List<String> targetedBusinessUnits,
      Instant firstAttempt, Instant lastAttempt, String severity) {}

  /** Users whose denied cross-BU attempts in the window meet or exceed the configured threshold. */
  @GetMapping("/anomalies")
  public Map<String, Object> anomalies(@RequestParam(required = false) Integer windowMinutes) {
    int window = windowMinutes == null ? props.getAnomalyWindowMinutes() : windowMinutes;
    Instant since = Instant.now().minus(window, ChronoUnit.MINUTES);

    List<AuditEvent> denials =
        audit.since(since).stream()
            .filter(e -> AuditEvent.DENIED.equals(e.outcome()))
            .toList();

    List<AnomalyReport> reports =
        denials.stream()
            .collect(Collectors.groupingBy(AuditEvent::userEmail))
            .entrySet()
            .stream()
            .filter(e -> e.getValue().size() >= props.getAnomalyDeniedThreshold())
            .map(
                e -> {
                  List<AuditEvent> list = e.getValue();
                  var sorted = list.stream().sorted(Comparator.comparing(AuditEvent::at)).toList();
                  return new AnomalyReport(
                      e.getKey(),
                      String.valueOf(sorted.get(0).role()),
                      list.size(),
                      list.stream().map(AuditEvent::businessUnit).distinct().sorted().toList(),
                      sorted.get(0).at(),
                      sorted.get(sorted.size() - 1).at(),
                      list.size() >= props.getAnomalyDeniedThreshold() * 3 ? "High" : "Medium");
                })
            .sorted(Comparator.comparingLong(AnomalyReport::deniedAttempts).reversed())
            .toList();

    return Map.of(
        "windowMinutes", window,
        "threshold", props.getAnomalyDeniedThreshold(),
        "totalDenialsInWindow", denials.size(),
        "anomalies", reports);
  }

  /** Access counts by data type, for the periodic HR Ops review of who reads what. */
  @GetMapping("/summary")
  public Map<String, Object> summary(@RequestParam(required = false) Integer hours) {
    Instant since = Instant.now().minus(hours == null ? 24 : hours, ChronoUnit.HOURS);
    List<AuditEvent> events = audit.since(since);
    return Map.of(
        "since", since.toString(),
        "total", events.size(),
        "byDataType",
            events.stream().collect(Collectors.groupingBy(AuditEvent::dataType, Collectors.counting())),
        "byOutcome",
            events.stream().collect(Collectors.groupingBy(AuditEvent::outcome, Collectors.counting())),
        "byUser",
            events.stream().collect(Collectors.groupingBy(AuditEvent::userEmail, Collectors.counting())));
  }
}
