package com.leadsquared.peopleinsights.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a user targets a BU outside their assignment.
 *
 * <p>Deliberately a 403 with a message, never an empty 200: the access rules require that typing
 * another BU's identifier returns an access-denied response rather than empty data, so that a
 * probe is distinguishable from a genuinely empty BU.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class AccessDeniedForBuException extends RuntimeException {

  private final String requestedBu;

  public AccessDeniedForBuException(String requestedBu, String message) {
    super(message);
    this.requestedBu = requestedBu;
  }

  public String getRequestedBu() {
    return requestedBu;
  }
}
