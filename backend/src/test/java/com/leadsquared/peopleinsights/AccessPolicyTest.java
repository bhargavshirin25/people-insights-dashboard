package com.leadsquared.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.CustomRole;
import com.leadsquared.peopleinsights.domain.Permission;
import com.leadsquared.peopleinsights.domain.Role;
import com.leadsquared.peopleinsights.repo.CustomRoleRepo;
import com.leadsquared.peopleinsights.security.AccessPolicy;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an identity resolves to, now that a role granted on the access page is the only source of access.
 *
 * <p>Two properties matter here. An address no role names must resolve to nothing, so access is granted
 * deliberately or not at all. And a role must decide access outright rather than adding to whatever the
 * user record's tier says, since a grant that can only ever widen is not access control, it is a promotion
 * button.
 */
class AccessPolicyTest {

  private final AppProperties props = new AppProperties();

  private AccessPolicy policyWith(CustomRole... roles) {
    CustomRoleRepo repo = mock(CustomRoleRepo.class);
    when(repo.findAll()).thenReturn(List.of(roles));
    return new AccessPolicy(repo, props);
  }

  private static CustomRole role(String name, List<Permission> permissions, String... members) {
    CustomRole role = new CustomRole(name, name, permissions);
    role.setMemberEmails(List.of(members));
    return role;
  }

  @Test
  @DisplayName("an address no role names resolves to no access, whatever tier its record carries")
  void noRoleMeansNoAccess() {
    AccessPolicy policy = policyWith();

    // A generous tier on the user record, to show it is not consulted.
    var resolved = policy.resolve("nobody@leadsquared.com", Role.CHRO, List.of("Engineering"));

    assertThat(resolved.permissions()).isEmpty();
    assertThat(resolved.businessUnits()).isEmpty();
    assertThat(resolved.sourceRoles()).isEmpty();
  }

  @Test
  @DisplayName("a role decides access outright, ignoring the tier on the user record")
  void roleDecidesOutright() {
    // A record carrying the HR Head tier, placed in an exits-only role, gets exits and nothing else.
    AccessPolicy policy =
        policyWith(
            role(
                "Exit analysis only",
                List.of(Permission.VIEW_EXIT),
                "anita@leadsquared.com"));

    var resolved = policy.resolve("anita@leadsquared.com", Role.HR_HEAD, List.of());

    assertThat(resolved.has(Permission.VIEW_EXIT)).isTrue();
    assertThat(resolved.has(Permission.VIEW_RISK)).isFalse();
    assertThat(resolved.has(Permission.SEE_INDIVIDUAL_PII)).isFalse();
    assertThat(resolved.has(Permission.VIEW_AUDIT)).isFalse();
    assertThat(resolved.sourceRoles()).containsExactly("Exit analysis only");
  }

  @Test
  @DisplayName("two roles on one address are unioned, permissions and business units alike")
  void rolesAreUnioned() {
    CustomRole exits = role("Exits", List.of(Permission.VIEW_EXIT), "shared@leadsquared.com");
    exits.setBusinessUnits(List.of("Engineering"));
    CustomRole risk =
        role(
            "Risk",
            List.of(Permission.VIEW_RISK, Permission.SEE_INDIVIDUAL_PII),
            "shared@leadsquared.com");
    risk.setBusinessUnits(List.of("Sales"));

    var resolved = policyWith(exits, risk).resolve("shared@leadsquared.com", Role.VIEWER, List.of());

    assertThat(resolved.permissions())
        .containsExactlyInAnyOrder(
            Permission.VIEW_EXIT, Permission.VIEW_RISK, Permission.SEE_INDIVIDUAL_PII);
    assertThat(resolved.businessUnits()).containsExactlyInAnyOrder("Engineering", "Sales");
    assertThat(resolved.sourceRoles()).containsExactlyInAnyOrder("Exits", "Risk");
  }

  @Test
  @DisplayName("a role marked for every business unit follows the configured list, not a snapshot of it")
  void allBusinessUnitsFollowsConfiguration() {
    CustomRole everywhere = role("Everywhere", List.of(Permission.VIEW_OVERVIEW), "eve@leadsquared.com");
    everywhere.setAllBusinessUnits(true);
    everywhere.setBusinessUnits(List.of("Engineering"));

    var resolved = policyWith(everywhere).resolve("eve@leadsquared.com", Role.VIEWER, List.of());

    assertThat(resolved.businessUnits()).isEqualTo(props.getBusinessUnits());
  }

  @Test
  @DisplayName("membership is matched without regard to case, since an IdP's casing is not ours to assume")
  void membershipIgnoresCase() {
    AccessPolicy policy =
        policyWith(role("Audit", List.of(Permission.VIEW_AUDIT), "Someone@LeadSquared.com"));

    assertThat(policy.resolve("someone@leadsquared.com", Role.VIEWER, List.of()).has(Permission.VIEW_AUDIT))
        .isTrue();
    assertThat(policy.resolve("SOMEONE@LEADSQUARED.COM", Role.VIEWER, List.of()).has(Permission.VIEW_AUDIT))
        .isTrue();
    assertThat(policy.resolve("someone.else@leadsquared.com", Role.VIEWER, List.of()).has(Permission.VIEW_AUDIT))
        .isFalse();
  }

  @Test
  @DisplayName("an unknown business unit on a role is dropped rather than reaching a query")
  void unknownBusinessUnitsAreDropped() {
    CustomRole role = role("Typo", List.of(Permission.VIEW_OVERVIEW), "typo@leadsquared.com");
    role.setBusinessUnits(List.of("Engineering", "Engineering Department", "sales"));

    var resolved = policyWith(role).resolve("typo@leadsquared.com", Role.VIEWER, List.of());

    // "sales" canonicalises; the invented unit does not survive.
    assertThat(resolved.businessUnits()).containsExactlyInAnyOrder("Engineering", "Sales");
  }

  @Test
  @DisplayName("the full-access shape grants every permission there is")
  void fullAccessCoversEveryPermission() {
    CustomRole full = role("Full access", Arrays.asList(Permission.values()), "me@leadsquared.com");
    full.setAllBusinessUnits(true);

    var resolved = policyWith(full).resolve("me@leadsquared.com", Role.VIEWER, List.of());

    assertThat(resolved.permissions()).containsAll(Arrays.asList(Permission.values()));
    assertThat(resolved.businessUnits()).isEqualTo(props.getBusinessUnits());
  }
}
