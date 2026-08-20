package com.leadsquared.peopleinsights.security;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.Permission;
import com.leadsquared.peopleinsights.domain.Role;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single chokepoint between a user's request and a data query.
 *
 * <p>The access rules require that cross-BU isolation hold "not just in the UI, but at the API and
 * data query layer". That is enforced structurally here: services never accept a BU string from a
 * request. They accept only the {@code List<String>} returned by {@link #resolve}, which is either
 * a subset of the caller's assignment or an exception. There is no code path that turns a raw
 * request parameter into a query predicate.
 */
@Component
public class ScopeGuard {

  private final AppProperties props;
  private final AuditService audit;
  private final AccessPolicy policy;

  public ScopeGuard(AppProperties props, AuditService audit, AccessPolicy policy) {
    this.props = props;
    this.audit = audit;
    this.policy = policy;
  }

  /** The current user's scope, with org-wide roles expanded to the full BU list. */
  public AccessScope currentScope() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null
        || !auth.isAuthenticated()
        || !(auth.getPrincipal() instanceof DashboardPrincipalHolder holder)) {
      throw new NotAuthenticatedException();
    }
    DashboardPrincipal p = holder.dashboard();
    // Resolved here, per request, rather than carried on the principal: a permission removed on the
    // access page has to take effect on the offender's next request, not at their next sign-in.
    AccessPolicy.EffectiveAccess effective = policy.resolve(p.email(), p.role(), p.assignedBus());
    return new AccessScope(
        p.email(),
        p.displayName(),
        p.role(),
        effective.businessUnits(),
        effective.permissions(),
        effective.businessUnits().size() >= props.getBusinessUnits().size());
  }

  /**
   * Asserts a permission, for the handful of actions that are not a business-unit read — logging a
   * retention action, editing a summary, reaching a configuration surface.
   *
   * <p>View and export requests are gated in {@code SecurityConfig} instead, where the URL rules live,
   * so a new endpoint under an existing prefix cannot be added without a permission attached to it.
   */
  public void require(Permission permission, String dataType, String action) {
    AccessScope scope = currentScope();
    if (!scope.has(permission)) {
      audit.denied(scope, null, dataType, action, "Missing permission " + permission.name());
      throw new AccessDeniedForBuException(
          null, "Access denied: this action requires the \"" + permission.label() + "\" permission.");
    }
  }

  /**
   * Resolves the BUs a request may read.
   *
   * @param requestedBu a single BU, or null/blank/"ALL" for everything in scope
   * @param dataType audit data-type tag
   * @param action audit action tag
   * @throws AccessDeniedForBuException when the BU is outside the caller's assignment or unknown
   */
  public Scoped resolve(String requestedBu, String dataType, String action) {
    AccessScope scope = currentScope();

    // A scope holding no data view at all cannot read a business unit. For the fixed ADMIN tier that is
    // its whole definition — configuration only — and the message says so; a custom role built with no
    // view ticked lands here too, which is the same refusal for the same reason.
    if (!scope.canReadEmployeeData()) {
      String reason =
          scope.role() == Role.ADMIN
              ? "The Admin role is limited to configuration and cannot read employee data."
              : "Access denied: none of your roles grant access to employee data.";
      audit.denied(scope, requestedBu, dataType, action, "No data-view permission");
      throw new AccessDeniedForBuException(requestedBu, reason);
    }

    boolean wantsAll = requestedBu == null || requestedBu.isBlank() || "ALL".equalsIgnoreCase(requestedBu);

    if (wantsAll) {
      // An HRBP with exactly one assigned BU lands on that BU; the org-wide roles get everything.
      audit.granted(scope, scope.isOrgWide() ? "ALL" : String.join(",", scope.allowedBus()), dataType, action, null);
      return new Scoped(scope, scope.allowedBus(), scope.isOrgWide() ? "ALL" : null);
    }

    String canonical = canonicaliseOne(requestedBu);
    if (canonical == null) {
      // Unknown identifier is treated as unauthorised, not as "no rows".
      audit.denied(scope, requestedBu, dataType, action, "Unknown business unit identifier");
      throw new AccessDeniedForBuException(
          requestedBu, "Access denied: '" + requestedBu + "' is not a recognised business unit.");
    }
    if (!scope.allowsBu(canonical)) {
      audit.denied(scope, canonical, dataType, action, "BU outside assignment");
      throw new AccessDeniedForBuException(
          canonical,
          "Access denied: you are not assigned to the " + canonical + " business unit.");
    }
    audit.granted(scope, canonical, dataType, action, null);
    return new Scoped(scope, List.of(canonical), canonical);
  }

  /** Guards an individual-grain read: rejects roles below HRBP outright. */
  public Scoped resolveIndividual(String requestedBu, String dataType, String action) {
    Scoped scoped = resolve(requestedBu, dataType, action);
    if (!scoped.scope().canSeeIndividualPii()) {
      audit.denied(scoped.scope(), requestedBu, dataType, action, "Individual data withheld");
      throw new AccessDeniedForBuException(
          requestedBu,
          "Access denied: individual employee data requires HRBP level or above.");
    }
    return scoped;
  }

  /** Guards an employee-scoped read: the employee's BU must be inside the caller's assignment. */
  public void assertEmployeeInScope(AccessScope scope, String employeeBu, String dataType, String action) {
    if (!scope.canSeeIndividualPii() || !scope.allowsBu(employeeBu)) {
      audit.denied(scope, employeeBu, dataType, action, "Employee outside assignment");
      throw new AccessDeniedForBuException(
          employeeBu, "Access denied: that employee is not in a business unit assigned to you.");
    }
    audit.granted(scope, employeeBu, dataType, action, null);
  }

  private List<String> canonicalise(List<String> input) {
    return input.stream().map(this::canonicaliseOne).filter(java.util.Objects::nonNull).toList();
  }

  /** Maps a case-insensitive request value onto the canonical BU name, or null if unknown. */
  private String canonicaliseOne(String bu) {
    if (bu == null) {
      return null;
    }
    String trimmed = bu.trim();
    return props.getBusinessUnits().stream()
        .filter(b -> b.equalsIgnoreCase(trimmed))
        .findFirst()
        .orElse(null);
  }

  /**
   * An authorised BU list plus the caller's scope.
   *
   * @param businessUnits the only BU values that may reach a query
   * @param selectedBu the single BU in focus, or null when the view spans the caller's whole scope
   */
  public record Scoped(AccessScope scope, List<String> businessUnits, String selectedBu) {

    /** Label for the view header and for exports. */
    public String label() {
      if (selectedBu != null && !"ALL".equals(selectedBu)) {
        return selectedBu;
      }
      return businessUnits.size() == 1 ? businessUnits.get(0) : "All Business Units";
    }
  }
}
