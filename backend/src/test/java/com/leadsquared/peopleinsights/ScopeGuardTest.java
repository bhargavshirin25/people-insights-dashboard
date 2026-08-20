package com.leadsquared.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.CustomRole;
import com.leadsquared.peopleinsights.domain.Permission;
import com.leadsquared.peopleinsights.domain.Role;
import com.leadsquared.peopleinsights.repo.CustomRoleRepo;
import com.leadsquared.peopleinsights.security.AccessDeniedForBuException;
import com.leadsquared.peopleinsights.security.AccessPolicy;
import com.leadsquared.peopleinsights.security.AuditService;
import com.leadsquared.peopleinsights.security.DashboardPrincipal;
import com.leadsquared.peopleinsights.security.NotAuthenticatedException;
import com.leadsquared.peopleinsights.security.ScopeGuard;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The cross-BU isolation rules, which are a precondition for go-live.
 *
 * <p>These assert behaviour the brief states in absolute terms: an unauthorised BU must return access
 * denied rather than empty data, a scope must not reach another BU by any route, and a scope without
 * individual rights must not receive individual-grain data.
 *
 * <p>Access is granted by a role naming an address, so each case here builds the role it is about. The
 * user record's tier is set too, and deliberately set to something generous — it grants nothing, and a
 * test that passes with a contradictory tier is a test that proves the tier is not consulted.
 */
class ScopeGuardTest {

  private static final String EMAIL = "user@leadsquared.com";

  private AuditService audit;
  private CustomRoleRepo roles;
  private AppProperties props;

  @BeforeEach
  void setUp() {
    props = new AppProperties();
    audit = mock(AuditService.class);
    roles = mock(CustomRoleRepo.class);
    when(roles.findAll()).thenReturn(List.of());
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  /** A guard whose policy sees exactly these roles. */
  private ScopeGuard guardWith(CustomRole... granted) {
    when(roles.findAll()).thenReturn(List.of(granted));
    return new ScopeGuard(props, audit, new AccessPolicy(roles, props));
  }

  private static CustomRole role(List<Permission> permissions, List<String> businessUnits) {
    CustomRole role = new CustomRole("Test role", "Test role", permissions);
    role.setBusinessUnits(businessUnits);
    role.setMemberEmails(List.of(EMAIL));
    return role;
  }

  private static CustomRole everywhere(List<Permission> permissions) {
    CustomRole role = new CustomRole("Org-wide role", "Org-wide role", permissions);
    role.setAllBusinessUnits(true);
    role.setMemberEmails(List.of(EMAIL));
    return role;
  }

  /** Signs in with a tier that, on its own, grants nothing. */
  private void signIn(Role tier, List<String> assignedBus) {
    var principal = new DashboardPrincipal(EMAIL, "Test User", tier, assignedBus);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
  }

  private static final List<Permission> READS_INDIVIDUALS =
      List.of(Permission.VIEW_OVERVIEW, Permission.VIEW_RISK, Permission.SEE_INDIVIDUAL_PII);

  @Test
  @DisplayName("no authenticated identity produces no data at all")
  void unauthenticatedIsRejected() {
    assertThatThrownBy(() -> guardWith().resolve("Engineering", "HEADCOUNT", "TEST"))
        .isInstanceOf(NotAuthenticatedException.class);
  }

  @Test
  @DisplayName("a scope reaches only the BU its role grants")
  void reachesOnlyGrantedBu() {
    ScopeGuard guard = guardWith(role(READS_INDIVIDUALS, List.of("Engineering")));
    signIn(Role.HRBP, List.of("Engineering"));

    var scoped = guard.resolve("Engineering", "HEADCOUNT", "TEST");

    assertThat(scoped.businessUnits()).containsExactly("Engineering");
    assertThat(scoped.selectedBu()).isEqualTo("Engineering");
    verify(audit).granted(any(), eq("Engineering"), eq("HEADCOUNT"), eq("TEST"), any());
  }

  @Test
  @DisplayName("another BU is denied, and denied loudly rather than returned empty")
  void otherBuIsDenied() {
    ScopeGuard guard = guardWith(role(READS_INDIVIDUALS, List.of("Engineering")));
    signIn(Role.HRBP, List.of("Engineering"));

    assertThatThrownBy(() -> guard.resolve("Sales", "COMPENSATION", "TEST"))
        .isInstanceOf(AccessDeniedForBuException.class)
        .hasMessageContaining("not assigned to the Sales business unit");

    verify(audit).denied(any(), eq("Sales"), eq("COMPENSATION"), eq("TEST"), anyString());
    verify(audit, never()).granted(any(), eq("Sales"), any(), any(), any());
  }

  @Test
  @DisplayName("an unknown BU identifier is unauthorised, not merely unmatched")
  void unknownBuIsDenied() {
    ScopeGuard guard = guardWith(role(READS_INDIVIDUALS, List.of("Engineering")));
    signIn(Role.HRBP, List.of("Engineering"));

    assertThatThrownBy(() -> guard.resolve("Engineering Department", "HEADCOUNT", "TEST"))
        .isInstanceOf(AccessDeniedForBuException.class)
        .hasMessageContaining("not a recognised business unit");
  }

  @Test
  @DisplayName("a differently cased BU resolves to the canonical one rather than being refused")
  void caseIsCanonicalised() {
    ScopeGuard guard = guardWith(role(READS_INDIVIDUALS, List.of("Engineering")));
    signIn(Role.HRBP, List.of("Engineering"));

    assertThat(guard.resolve("engineering", "HEADCOUNT", "TEST").businessUnits())
        .containsExactly("Engineering");
  }

  @Test
  @DisplayName("asking for everything returns only what the role granted")
  void askingForAllStaysScoped() {
    ScopeGuard guard = guardWith(role(READS_INDIVIDUALS, List.of("Engineering", "Product")));
    signIn(Role.CHRO, List.of());

    var scoped = guard.resolve(null, "HEADCOUNT", "TEST");

    assertThat(scoped.businessUnits()).containsExactlyInAnyOrder("Engineering", "Product");
    assertThat(scoped.scope().isOrgWide()).isFalse();
  }

  @Test
  @DisplayName("a role marked for every business unit resolves to all of them")
  void orgWideRoleGetsEverything() {
    ScopeGuard guard = guardWith(everywhere(READS_INDIVIDUALS));
    signIn(Role.VIEWER, List.of());

    var scoped = guard.resolve(null, "HEADCOUNT", "TEST");

    assertThat(scoped.businessUnits()).isEqualTo(props.getBusinessUnits());
    assertThat(scoped.scope().isOrgWide()).isTrue();
  }

  @Test
  @DisplayName("an address no role names reaches nothing, whatever its tier says")
  void noRoleMeansNoAccess() {
    ScopeGuard guard = guardWith();
    signIn(Role.CHRO, List.of("Engineering"));

    assertThatThrownBy(() -> guard.resolve("Engineering", "HEADCOUNT", "TEST"))
        .isInstanceOf(AccessDeniedForBuException.class)
        .hasMessageContaining("none of your roles grant access to employee data");
  }

  @Test
  @DisplayName("a role holding only configuration permissions cannot read employee data")
  void configurationOnlyRoleHasNoDataAccess() {
    ScopeGuard guard =
        guardWith(everywhere(List.of(Permission.MANAGE_CONFIG, Permission.MANAGE_ACCESS)));
    signIn(Role.ADMIN, List.of());

    assertThatThrownBy(() -> guard.resolve("Engineering", "COMPENSATION", "TEST"))
        .isInstanceOf(AccessDeniedForBuException.class)
        .hasMessageContaining("limited to configuration");

    assertThatThrownBy(() -> guard.resolve(null, "HEADCOUNT", "TEST"))
        .isInstanceOf(AccessDeniedForBuException.class);
  }

  @Test
  @DisplayName("a role without individual rights gets aggregates but is refused individual-grain data")
  void aggregateOnlyRoleCannotSeeIndividuals() {
    ScopeGuard guard = guardWith(everywhere(List.of(Permission.VIEW_OVERVIEW, Permission.VIEW_RISK)));
    signIn(Role.HRBP, List.of());

    // Aggregate reads are allowed.
    assertThat(guard.resolve("Engineering", "HEADCOUNT", "TEST").businessUnits())
        .containsExactly("Engineering");

    // The individual-grain gate refuses the same request.
    assertThatThrownBy(() -> guard.resolveIndividual("Engineering", "RISK_REGISTER", "TEST"))
        .isInstanceOf(AccessDeniedForBuException.class)
        .hasMessageContaining("requires HRBP level or above");
  }

  @Test
  @DisplayName("an employee outside the granted BUs is out of scope even by employee id")
  void employeeOutsideScopeIsRefused() {
    ScopeGuard guard = guardWith(role(READS_INDIVIDUALS, List.of("Engineering")));
    signIn(Role.HRBP, List.of("Engineering"));
    var scope = guard.currentScope();

    assertThatThrownBy(() -> guard.assertEmployeeInScope(scope, "Sales", "RISK_DETAIL", "TEST"))
        .isInstanceOf(AccessDeniedForBuException.class)
        .hasMessageContaining("not in a business unit assigned to you");

    guard.assertEmployeeInScope(scope, "Engineering", "RISK_DETAIL", "TEST");
  }

  @Test
  @DisplayName("a permission the role does not hold is refused and audited")
  void missingPermissionIsRefused() {
    ScopeGuard guard = guardWith(everywhere(List.of(Permission.VIEW_OVERVIEW)));
    signIn(Role.HRBP, List.of());

    assertThatThrownBy(
            () -> guard.require(Permission.LOG_RETENTION_ACTION, "RETENTION_ACTION", "TEST"))
        .isInstanceOf(AccessDeniedForBuException.class)
        .hasMessageContaining("Log retention actions");

    verify(audit).denied(any(), any(), eq("RETENTION_ACTION"), eq("TEST"), anyString());
  }
}
