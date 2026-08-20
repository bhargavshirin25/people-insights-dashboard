package com.leadsquared.peopleinsights.security;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.Permission;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * HTTP security.
 *
 * <p>Three rules from the access-control mandate are implemented here rather than in application
 * code: nothing is reachable unauthenticated, the Admin role is fenced out of data endpoints, and
 * responses carrying employee data are marked no-store so no shared or intermediary cache can hold
 * identifiable data between sessions.
 *
 * <p>Entra (Azure AD) SSO is wired as an OAuth2 login and becomes the only sign-in path as soon as
 * {@code spring.security.oauth2.client.registration.azure} is populated. Until then the seeded dev
 * login is available, gated on {@code dashboard.dev-login-enabled}.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

  private final AppProperties props;
  private final AccessPolicy policy;

  public SecurityConfig(AppProperties props, AccessPolicy policy) {
    this.props = props;
    this.policy = policy;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Required for concurrent-session control to notice invalidated sessions. Without it a signed-out
   * session lingers in the registry and the one-session-per-user rule stops holding.
   */
  @Bean
  public HttpSessionEventPublisher httpSessionEventPublisher() {
    return new HttpSessionEventPublisher();
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http, EntraOidcUserService oidcUserService)
      throws Exception {

    var csrfHandler = new CsrfTokenRequestAttributeHandler();
    csrfHandler.setCsrfRequestAttributeName(null); // opt out of deferred tokens for a plain SPA

    http.csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfHandler))
        .sessionManagement(
            session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(fixation -> fixation.newSession())
                    // One authenticated session per browser; a new sign-in invalidates the old.
                    .maximumSessions(1)
                    .maxSessionsPreventsLogin(false))
        /*
         * Every endpoint that carries employee data or configuration is gated on a permission here,
         * rather than on a role inside each controller.
         *
         * One reason: a permission set can now be assembled by an administrator on the access page, so a
         * role name is no longer a reliable predicate. The better reason is that this file is the list
         * of what the application exposes — a new endpoint added under an existing prefix inherits its
         * prefix's permission instead of quietly defaulting to "any signed-in user", which is what a
         * per-controller annotation lets happen when somebody forgets one.
         *
         * Order matters: the narrower matcher goes first, since the first match wins.
         */
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/auth/login", "/api/auth/session", "/api/auth/microsoft")
                    .permitAll()
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    // Roles, their permissions and their members. The permission that grants the rest.
                    .requestMatchers("/api/access/**")
                    .access(requires(Permission.MANAGE_ACCESS))
                    // Configuration surface. BU->HRBP mapping lives behind this.
                    .requestMatchers("/api/admin/**", "/api/data-source/**")
                    .access(requires(Permission.MANAGE_CONFIG))
                    .requestMatchers("/api/audit/**")
                    .access(requires(Permission.VIEW_AUDIT))
                    .requestMatchers(HttpMethod.POST, "/api/risk/actions/**")
                    .access(requires(Permission.LOG_RETENTION_ACTION))
                    .requestMatchers("/api/risk/**")
                    .access(requires(Permission.VIEW_RISK))
                    .requestMatchers("/api/insights/exit")
                    .access(requires(Permission.VIEW_EXIT))
                    .requestMatchers("/api/insights/performance")
                    .access(requires(Permission.VIEW_PERFORMANCE))
                    .requestMatchers("/api/insights/leave-attendance")
                    .access(requires(Permission.VIEW_LEAVE_ATTENDANCE))
                    .requestMatchers("/api/insights/compensation-bands")
                    .access(requires(Permission.SEE_COMPENSATION))
                    .requestMatchers("/api/dashboard/heatmap")
                    .access(requires(Permission.VIEW_HEATMAP))
                    .requestMatchers("/api/dashboard/**")
                    .access(requires(Permission.VIEW_OVERVIEW))
                    .requestMatchers("/api/narrative/regenerate", "/api/narrative/edit")
                    .access(requires(Permission.EDIT_NARRATIVE))
                    .requestMatchers("/api/narrative/**")
                    .access(requires(Permission.VIEW_OVERVIEW))
                    .requestMatchers("/api/chat/**")
                    .access(requires(Permission.USE_ASSISTANT))
                    .requestMatchers("/api/export/**")
                    .access(requires(Permission.EXPORT_DECK))
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (request, response, authException) ->
                            writeJson(
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "Authentication required before any dashboard data can be accessed."))
                    .accessDeniedHandler(
                        (request, response, deniedException) ->
                            // A rejected CSRF token arrives here as an AccessDeniedException too.
                            // Reporting it as a role problem sends the reader looking at permissions
                            // for what is a missing request header.
                            writeJson(
                                response,
                                HttpStatus.FORBIDDEN,
                                deniedException instanceof CsrfException
                                    ? "CSRF token missing or invalid. Send the value of the "
                                        + "XSRF-TOKEN cookie in an X-XSRF-TOKEN header on every "
                                        + "state-changing request."
                                    : "Access denied for this role.")))
        .headers(
            headers ->
                headers.cacheControl(Customizer.withDefaults())
                    .frameOptions(frame -> frame.deny())
                    .contentTypeOptions(Customizer.withDefaults()));

    // Only register the SSO path once a client registration exists, otherwise Spring fails to start.
    if (oidcUserService.isSsoConfigured()) {
      http.oauth2Login(
          login -> login.userInfoEndpoint(info -> info.oidcUserService(oidcUserService)));
    }

    if (!props.isDevLoginEnabled() && !oidcUserService.isSsoConfigured()) {
      throw new IllegalStateException(
          "No sign-in method available: configure Entra SSO, or enable dashboard.dev-login-enabled to "
              + "allow the placeholder sign-in.");
    }

    // Said once, loudly, at every start: while this is the state, the sign-in page is not a check.
    if (!oidcUserService.isSsoConfigured() && props.isDevLoginEnabled()) {
      log.warn(
          "Entra SSO is NOT configured. The Microsoft sign-in button signs everyone in as the "
              + "placeholder identity {} ({}) with no credential check. Configure Entra and run with "
              + "the 'sso' profile before this instance carries real data.",
          props.getPlaceholderSignInEmail(),
          props.getPlaceholderSignInName());
    }

    return http.build();
  }

  /**
   * Grants a request when the caller's resolved permission set contains {@code permission}.
   *
   * <p>Resolved from {@link AccessPolicy} on each request rather than read from the authentication's
   * granted authorities. Authorities are fixed when the session is created, so an administrator
   * revoking a permission would not reach a session already open — and a session outliving its grant is
   * exactly what the audit rules exist to prevent.
   */
  private AuthorizationManager<RequestAuthorizationContext> requires(Permission permission) {
    return (authentication, context) -> {
      Authentication auth = authentication.get();
      if (auth == null
          || !auth.isAuthenticated()
          || !(auth.getPrincipal() instanceof DashboardPrincipalHolder holder)) {
        return new AuthorizationDecision(false);
      }
      DashboardPrincipal p = holder.dashboard();
      return new AuthorizationDecision(
          policy.resolve(p.email(), p.role(), p.assignedBus()).has(permission));
    };
  }

  private static void writeJson(HttpServletResponse response, HttpStatus status, String message)
      throws java.io.IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader("Cache-Control", "no-store");
    response
        .getWriter()
        .write("{\"error\":\"" + status.getReasonPhrase() + "\",\"message\":\"" + message + "\"}");
  }
}
