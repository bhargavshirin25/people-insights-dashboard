package com.leadsquared.peopleinsights.security;

import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * An Entra-authenticated user carrying the application's own role and BU assignment.
 *
 * <p>Only the claims Spring needs are delegated to the underlying OIDC user; authorities come from
 * the dashboard role, never from IdP group claims, so access cannot widen because a directory group
 * changed.
 */
public class EntraDashboardUser implements OidcUser, DashboardPrincipalHolder {

  private final OidcUser delegate;
  private final DashboardPrincipal principal;

  public EntraDashboardUser(OidcUser delegate, DashboardPrincipal principal) {
    this.delegate = delegate;
    this.principal = principal;
  }

  @Override
  public DashboardPrincipal dashboard() {
    return principal;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return principal.getAuthorities();
  }

  @Override
  public Map<String, Object> getAttributes() {
    return delegate.getAttributes();
  }

  @Override
  public Map<String, Object> getClaims() {
    return delegate.getClaims();
  }

  @Override
  public OidcUserInfo getUserInfo() {
    return delegate.getUserInfo();
  }

  @Override
  public OidcIdToken getIdToken() {
    return delegate.getIdToken();
  }

  @Override
  public String getName() {
    return principal.email();
  }
}
