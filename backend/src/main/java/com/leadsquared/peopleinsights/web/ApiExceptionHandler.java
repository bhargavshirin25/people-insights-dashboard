package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.ai.AssistantUnavailableException;
import com.leadsquared.peopleinsights.security.AccessDeniedForBuException;
import com.leadsquared.peopleinsights.security.NotAuthenticatedException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Uniform error bodies.
 *
 * <p>A cross-BU attempt returns 403 with an explicit message. That is the intended behaviour, not a
 * leak: the rules require an access-denied response rather than empty data, so a probe is
 * distinguishable from a BU that genuinely has no rows.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(AccessDeniedForBuException.class)
  public ResponseEntity<Map<String, Object>> onBuDenied(AccessDeniedForBuException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            body(
                "access_denied",
                e.getMessage(),
                Map.of("requestedBusinessUnit", String.valueOf(e.getRequestedBu()))));
  }

  @ExceptionHandler(NotAuthenticatedException.class)
  public ResponseEntity<Map<String, Object>> onUnauthenticated(NotAuthenticatedException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body("unauthenticated", e.getMessage(), Map.of()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> onBadRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(body("bad_request", e.getMessage(), Map.of()));
  }

  /**
   * The assistant is a dashboard feature that can be absent — no API key on this instance — or briefly
   * unreachable. Either way the panel says so rather than showing an empty reply, so this is reported as
   * a service state and not logged as a fault.
   */
  @ExceptionHandler(AssistantUnavailableException.class)
  public ResponseEntity<Map<String, Object>> onAssistantUnavailable(AssistantUnavailableException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(body("assistant_unavailable", e.getMessage(), Map.of()));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Map<String, Object>> onIllegalState(IllegalStateException e) {
    log.error("Request failed", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(body("server_error", e.getMessage(), Map.of()));
  }

  private Map<String, Object> body(String code, String message, Map<String, Object> extra) {
    var map = new java.util.LinkedHashMap<String, Object>();
    map.put("error", code);
    map.put("message", message);
    map.put("at", Instant.now().toString());
    map.putAll(extra);
    return map;
  }
}
