package com.leadsquared.peopleinsights.security;

/**
 * Implemented by every principal type the app can authenticate, so authorisation code reads the
 * same {@link DashboardPrincipal} whether the user arrived via Entra SSO or the dev login.
 */
public interface DashboardPrincipalHolder {

  DashboardPrincipal dashboard();
}
