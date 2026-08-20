package com.leadsquared.peopleinsights.security;

import com.leadsquared.peopleinsights.domain.AppUser;
import com.leadsquared.peopleinsights.domain.Role;
import com.leadsquared.peopleinsights.repo.AppUserRepo;
import com.leadsquared.peopleinsights.repo.BuAssignmentRepo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * Maps an Entra (Azure AD) identity onto a {@link DashboardPrincipal}.
 *
 * <p>The IdP supplies identity only. Role and BU assignment come from this application's own
 * {@code app_users} and {@code bu_assignments} collections, so a valid corporate login is not by
 * itself enough to see data: an unprovisioned user is rejected rather than defaulted to a role.
 */
@Service
public class EntraOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  private final OidcUserService delegate = new OidcUserService();
  private final AppUserRepo users;
  private final BuAssignmentRepo assignments;
  private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

  public EntraOidcUserService(
      AppUserRepo users,
      BuAssignmentRepo assignments,
      ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
    this.users = users;
    this.assignments = assignments;
    this.clientRegistrations = clientRegistrations;
  }

  /** True when an OAuth2 client registration is present, i.e. tenant config has been filled in. */
  public boolean isSsoConfigured() {
    return clientRegistrations.getIfAvailable() != null;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
    OidcUser oidcUser = delegate.loadUser(request);
    String email = firstNonBlank(oidcUser.getEmail(), oidcUser.getPreferredUsername());
    if (email == null) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error("no_email"), "Entra token carried no email or preferred_username claim.");
    }

    AppUser user =
        users
            .findByEmailIgnoreCase(email)
            .filter(AppUser::isEnabled)
            .orElseThrow(
                () ->
                    new OAuth2AuthenticationException(
                        new OAuth2Error("not_provisioned"),
                        "No dashboard role is provisioned for " + email + "."));

    user.setLastLoginAt(Instant.now());
    users.save(user);

    return new EntraDashboardUser(
        oidcUser,
        new DashboardPrincipal(
            user.getEmail(),
            firstNonBlank(user.getDisplayName(), oidcUser.getFullName(), email),
            user.getRole(),
            resolveBus(user)));
  }

  /**
   * BU assignment precedence: the admin-managed mapping wins over the copy denormalised onto the
   * user record, so revoking access in one place is sufficient.
   */
  private List<String> resolveBus(AppUser user) {
    if (user.getRole() != Role.HRBP) {
      return user.getAssignedBus();
    }
    Optional<List<String>> mapped =
        assignments.findByHrbpEmailIgnoreCase(user.getEmail()).map(a -> a.getBusinessUnits());
    return mapped.filter(list -> !list.isEmpty()).orElseGet(user::getAssignedBus);
  }

  static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }
}
