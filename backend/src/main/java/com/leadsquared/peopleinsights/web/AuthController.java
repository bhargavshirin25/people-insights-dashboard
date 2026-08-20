package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.AppUser;
import com.leadsquared.peopleinsights.domain.Role;
import com.leadsquared.peopleinsights.security.AccessPolicy;
import com.leadsquared.peopleinsights.repo.AppUserRepo;
import com.leadsquared.peopleinsights.repo.BuAssignmentRepo;
import com.leadsquared.peopleinsights.security.AuditService;
import com.leadsquared.peopleinsights.security.DashboardPrincipal;
import com.leadsquared.peopleinsights.security.DashboardPrincipalHolder;
import com.leadsquared.peopleinsights.security.EntraOidcUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in, session status and sign-out.
 *
 * <p>One path: Microsoft. Entra SSO is the production mechanism, and while no tenant credentials exist the
 * same button signs the caller in as the configured placeholder identity — see {@link #microsoft}. There is
 * no password sign-in: no account holds a password hash, so the endpoint that checked one has been removed
 * rather than left behind a flag.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AppUserRepo users;
  private final BuAssignmentRepo assignments;
  private final AppProperties props;
  private final AuditService audit;
  private final EntraOidcUserService oidc;
  private final AccessPolicy policy;

  public AuthController(
      AppUserRepo users,
      BuAssignmentRepo assignments,
      AppProperties props,
      AuditService audit,
      EntraOidcUserService oidc,
      AccessPolicy policy) {
    this.users = users;
    this.assignments = assignments;
    this.props = props;
    this.audit = audit;
    this.oidc = oidc;
    this.policy = policy;
  }

  /**
   * @param permissions the resolved permission set, so the shell can hide what the API would refuse
   * @param customRoles the custom roles that decided it, empty when the fixed tier's default did
   */
  public record SessionResponse(
      boolean authenticated,
      String email,
      String displayName,
      Role role,
      List<String> assignedBus,
      String defaultBusinessUnit,
      boolean canSeeIndividualPii,
      boolean orgWide,
      boolean devLoginEnabled,
      boolean ssoConfigured,
      String savedFilterJson,
      List<String> permissions,
      List<String> customRoles) {}

  /** The current session, or an unauthenticated marker plus which sign-in methods are available. */
  @GetMapping("/session")
  public SessionResponse session() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null
        || !auth.isAuthenticated()
        || !(auth.getPrincipal() instanceof DashboardPrincipalHolder holder)) {
      return new SessionResponse(
          false, null, null, null, List.of(), null, false, false,
          props.isDevLoginEnabled(), oidc.isSsoConfigured(), null, List.of(), List.of());
    }
    DashboardPrincipal p = holder.dashboard();
    String savedFilters =
        users.findByEmailIgnoreCase(p.email()).map(AppUser::getSavedFilterJson).orElse(null);

    // The same resolution the guard and the URL rules use, so what the shell renders and what the API
    // allows cannot drift apart. Hiding a link remains a convenience; the refusal is server-side.
    AccessPolicy.EffectiveAccess effective = policy.resolve(p.email(), p.role(), p.assignedBus());
    boolean orgWide = effective.businessUnits().size() >= props.getBusinessUnits().size();
    List<String> scopedBus = effective.businessUnits();

    return new SessionResponse(
        true,
        p.email(),
        p.displayName(),
        p.role(),
        scopedBus,
        // A single-unit scope lands on that unit; an org-wide one lands on the aggregate view.
        !orgWide && scopedBus.size() == 1 ? scopedBus.get(0) : null,
        effective.has(com.leadsquared.peopleinsights.domain.Permission.SEE_INDIVIDUAL_PII),
        orgWide,
        props.isDevLoginEnabled(),
        oidc.isSsoConfigured(),
        savedFilters,
        effective.permissions().stream().map(Enum::name).sorted().toList(),
        effective.sourceRoles());
  }

  /**
   * The Microsoft sign-in button.
   *
   * <p>Two behaviours, and which one is live depends only on configuration. With Entra configured this
   * hands back the authorisation URL and the browser goes off to Microsoft. Without it — the state this
   * instance is in, because there are no tenant credentials yet — it signs the caller in as the
   * placeholder identity from {@code dashboard.placeholder-sign-in-*} so the dashboard can be used
   * before the tenant exists.
   *
   * <p>The second behaviour is an authentication bypass: anyone who can reach this endpoint becomes that
   * user. It is gated on {@code dashboard.dev-login-enabled}, which the {@code sso} profile turns off,
   * and it is recorded in the audit trail as a placeholder sign-in rather than as an SSO one, so no
   * reader of the trail can mistake it for a real Entra authentication. Removing it is the first step of
   * the go-live checklist once credentials arrive.
   */
  @PostMapping("/microsoft")
  public ResponseEntity<?> microsoft(HttpServletRequest http) {
    if (oidc.isSsoConfigured()) {
      // The real handshake happens on the backend origin; Next proxies /oauth2/** to it.
      return ResponseEntity.ok(
          Map.of("redirect", "/oauth2/authorization/azure", "placeholder", false));
    }
    if (!props.isDevLoginEnabled()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(
              Map.of(
                  "error", "sso_not_configured",
                  "message",
                      "Microsoft sign-in is not configured on this instance and the placeholder is disabled."));
    }

    String email = props.getPlaceholderSignInEmail();
    AppUser user =
        users
            .findByEmailIgnoreCase(email)
            .orElseGet(() -> new AppUser(email, props.getPlaceholderSignInName(), Role.HR_HEAD, List.of()));
    // The display name follows configuration, so correcting it does not need a database edit.
    if (!props.getPlaceholderSignInName().equals(user.getDisplayName())) {
      user.setDisplayName(props.getPlaceholderSignInName());
    }
    if (!user.isEnabled()) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(Map.of("error", "account_disabled", "message", "That account is disabled."));
    }

    establishSession(users.save(user), http, "Placeholder Microsoft sign-in — Entra not configured");
    return ResponseEntity.ok(session());
  }

  /**
   * Puts an authenticated principal into a fresh session.
   *
   * <p>A new session per sign-in, so nothing — filter state, CSRF token, a previous identity — carries
   * over from whoever used this browser last.
   */
  private void establishSession(AppUser user, HttpServletRequest http, String auditDetail) {
    DashboardPrincipal principal =
        new DashboardPrincipal(
            user.getEmail(), user.getDisplayName(), user.getRole(), resolveBus(user));

    HttpSession old = http.getSession(false);
    if (old != null) {
      old.invalidate();
    }
    HttpSession session = http.getSession(true);

    var token =
        UsernamePasswordAuthenticationToken.authenticated(
            principal, null, principal.getAuthorities());
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(token);
    SecurityContextHolder.setContext(context);
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

    user.setLastLoginAt(Instant.now());
    users.save(user);

    audit.event(
        user.getEmail(),
        user.getRole(),
        String.join(",", principal.assignedBus()),
        "AUTH",
        "SIGN_IN",
        "GRANTED",
        auditDetail);
  }

  @PostMapping("/logout")
  public ResponseEntity<?> logout(HttpServletRequest http) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof DashboardPrincipalHolder holder) {
      var p = holder.dashboard();
      audit.event(p.email(), p.role(), null, "AUTH", "SIGN_OUT", "GRANTED", null);
    }
    HttpSession session = http.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    return ResponseEntity.ok(Map.of("signedOut", true));
  }

  /** The admin-managed mapping wins over the copy denormalised onto the user record. */
  private List<String> resolveBus(AppUser user) {
    if (user.getRole() != Role.HRBP) {
      return user.getAssignedBus();
    }
    return assignments
        .findByHrbpEmailIgnoreCase(user.getEmail())
        .map(a -> a.getBusinessUnits())
        .filter(list -> !list.isEmpty())
        .orElseGet(user::getAssignedBus);
  }
}
