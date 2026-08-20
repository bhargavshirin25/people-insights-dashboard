package com.leadsquared.peopleinsights.security;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.AuditEvent;
import com.leadsquared.peopleinsights.domain.Role;
import com.leadsquared.peopleinsights.repo.AuditRepo;
import com.leadsquared.peopleinsights.repo.Store;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes the mandatory audit trail and raises the anomalous-access alert.
 *
 * <p>Every data read passes through here via {@link ScopeGuard}, including denied ones — a denial
 * is the more interesting event, since a run of them from one user is exactly the cross-BU probing
 * pattern the rules require an alert on.
 */
@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);
  private static final Logger alertLog = LoggerFactory.getLogger("AUDIT_ALERT");

  private final AuditRepo repo;
  private final Store store;
  private final AppProperties props;

  public AuditService(AuditRepo repo, Store store, AppProperties props) {
    this.repo = repo;
    this.store = store;
    this.props = props;
  }

  public void granted(AccessScope scope, String bu, String dataType, String action, String detail) {
    write(scope.email(), scope.role(), bu, dataType, action, AuditEvent.GRANTED, detail);
  }

  public void denied(AccessScope scope, String bu, String dataType, String action, String detail) {
    write(scope.email(), scope.role(), bu, dataType, action, AuditEvent.DENIED, detail);
    checkForAnomaly(scope);
  }

  public void event(
      String email, Role role, String bu, String dataType, String action, String outcome, String detail) {
    write(email, role, bu, dataType, action, outcome, detail);
  }

  private void write(
      String email,
      Role role,
      String bu,
      String dataType,
      String action,
      String outcome,
      String detail) {
    String path = null;
    String ip = null;
    HttpServletRequest req = currentRequest();
    if (req != null) {
      path = req.getRequestURI();
      ip = clientIp(req);
    }
    try {
      store.insert(
          new AuditEvent(
              UUID.randomUUID().toString(),
              Instant.now(),
              email,
              role,
              bu == null ? "ALL" : bu,
              dataType,
              action,
              outcome,
              detail,
              path,
              ip));
    } catch (RuntimeException e) {
      // An audit write failure must be loud but must not mask the user's actual request outcome.
      log.error("Failed to persist audit event for {} on {}/{}", email, dataType, bu, e);
    }
  }

  /**
   * Alerts when one user accumulates repeated denials inside the configured window — the
   * "HRBP repeatedly attempting to access another BU" pattern.
   */
  private void checkForAnomaly(AccessScope scope) {
    try {
      Instant since = Instant.now().minus(props.getAnomalyWindowMinutes(), ChronoUnit.MINUTES);
      List<AuditEvent> denials =
          repo.findByUserEmailAndOutcomeAndAtAfter(scope.email(), AuditEvent.DENIED, since);
      if (denials.size() >= props.getAnomalyDeniedThreshold()) {
        alertLog.warn(
            "ANOMALOUS ACCESS: {} ({}) recorded {} denied cross-BU attempts in the last {} minutes",
            scope.email(),
            scope.role(),
            denials.size(),
            props.getAnomalyWindowMinutes());
      }
    } catch (RuntimeException e) {
      log.warn("Anomaly check failed for {}", scope.email(), e);
    }
  }

  public List<AuditEvent> recent() {
    return repo.findTop500ByOrderByAtDesc();
  }

  public List<AuditEvent> since(Instant instant) {
    return repo.findSinceOrderByAtDesc(instant);
  }

  private static HttpServletRequest currentRequest() {
    var attrs = RequestContextHolder.getRequestAttributes();
    return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
  }

  private static String clientIp(HttpServletRequest req) {
    String forwarded = req.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return req.getRemoteAddr();
  }
}
