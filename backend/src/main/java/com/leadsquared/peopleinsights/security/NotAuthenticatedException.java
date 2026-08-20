package com.leadsquared.peopleinsights.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No verified identity on the request — no dashboard data may be produced. */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class NotAuthenticatedException extends RuntimeException {

  public NotAuthenticatedException() {
    super("Authentication required before any dashboard data can be accessed.");
  }
}
