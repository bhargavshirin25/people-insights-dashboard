package com.leadsquared.peopleinsights.security;

import com.leadsquared.peopleinsights.domain.Role;
import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * The authenticated user as the application sees them: identity from the IdP, authorisation
 * (role and BU assignment) resolved from the app's own configuration at sign-in.
 *
 * <p>Both sign-in paths — Entra OIDC and the seeded dev login — produce this same principal, so
 * every downstream authorisation decision is identical regardless of how the user authenticated.
 */
public record DashboardPrincipal(
    String email, String displayName, Role role, List<String> assignedBus, Map<String, Object> attributes)
    implements Principal, OAuth2User, DashboardPrincipalHolder {

  public DashboardPrincipal(String email, String displayName, Role role, List<String> assignedBus) {
    this(email, displayName, role, assignedBus == null ? List.of() : List.copyOf(assignedBus), Map.of());
  }

  @Override
  public DashboardPrincipal dashboard() {
    return this;
  }

  @Override
  public String getName() {
    return email;
  }

  @Override
  public Map<String, Object> getAttributes() {
    return attributes == null ? Map.of() : attributes;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority(role.authority()));
  }
}
