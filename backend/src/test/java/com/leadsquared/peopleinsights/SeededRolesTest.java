package com.leadsquared.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.config.DataSeeder;
import com.leadsquared.peopleinsights.domain.CustomRole;
import com.leadsquared.peopleinsights.domain.Permission;
import com.leadsquared.peopleinsights.repo.AppUserRepo;
import com.leadsquared.peopleinsights.repo.CustomRoleRepo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What the application provisions for itself at startup.
 *
 * <p>Both seeded roles are load-bearing in opposite directions, which is why their shape is asserted rather
 * than left to a reading of the seeder. The full-access role is what guarantees somebody can always reach
 * the access page, so it has to hold every permission there is — including any added after it was written.
 * The data-source role is the counter-example the separation rests on: a configuration job that opens a
 * screen and reads no employee data.
 */
class SeededRolesTest {

  private CustomRoleRepo roles;
  private DataSeeder seeder;
  private final List<CustomRole> saved = new ArrayList<>();

  @BeforeEach
  void setUp() {
    AppUserRepo users = mock(AppUserRepo.class);
    when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());

    roles = mock(CustomRoleRepo.class);
    when(roles.findByNameIgnoreCase(any())).thenReturn(Optional.empty());
    when(roles.save(any(CustomRole.class)))
        .thenAnswer(
            invocation -> {
              CustomRole role = invocation.getArgument(0);
              saved.add(role);
              return role;
            });

    seeder = new DataSeeder(users, roles, new AppProperties());
    seeder.seed();
  }

  private CustomRole role(String name) {
    return saved.stream()
        .filter(r -> r.getName().equalsIgnoreCase(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("The seeder did not create a \"" + name + "\" role"));
  }

  @Test
  @DisplayName("the full-access role holds every permission that exists, not a list frozen when it was written")
  void fullAccessMeansEveryPermission() {
    CustomRole full = role("Full access");

    assertThat(full.getPermissions()).containsExactlyInAnyOrder(Permission.values());
    assertThat(full.isAllBusinessUnits()).isTrue();
    assertThat(full.isSystemRole()).isTrue();
    // Without a member it would grant nothing, and the access page would be unreachable.
    assertThat(full.getMemberEmails()).isNotEmpty();
  }

  @Test
  @DisplayName("the configured addresses are the ones the full-access role names")
  void fullAccessNamesTheConfiguredAddresses() {
    assertThat(role("Full access").getMemberEmails())
        .containsExactlyInAnyOrderElementsOf(
            new AppProperties().getFullAccessEmails().stream().map(String::toLowerCase).toList());
  }

  @Test
  @DisplayName("the data-source role opens its screen and reads no employee data")
  void dataSourceRoleIsConfigurationOnly() {
    CustomRole dataSource = role("Data source manager");

    assertThat(dataSource.getPermissions()).containsExactly(Permission.MANAGE_CONFIG);
    // The two permissions it must not quietly acquire: employee data, and the record of who read it.
    assertThat(dataSource.getPermissions()).doesNotContain(Permission.VIEW_AUDIT);
    assertThat(Permission.dataViews()).noneMatch(dataSource.getPermissions()::contains);
    assertThat(dataSource.isAllBusinessUnits()).isFalse();
    assertThat(dataSource.getBusinessUnits()).isEmpty();
  }

  @Test
  @DisplayName("the data-source role is granted to nobody at startup, and is an ordinary role")
  void dataSourceRoleStartsEmpty() {
    CustomRole dataSource = role("Data source manager");

    // A role grants nothing until an address is named in it, so seeding one hands out no access.
    assertThat(dataSource.getMemberEmails()).isEmpty();
    // Not a system role: an administrator can rename, re-scope or delete it.
    assertThat(dataSource.isSystemRole()).isFalse();
  }

  @Test
  @DisplayName("an existing role of the same name is left alone, so an edit survives a restart")
  void existingRolesAreNotOverwritten() {
    saved.clear();
    CustomRole edited = new CustomRole("Data source manager", "renamed by an admin", List.of());
    when(roles.findByNameIgnoreCase("Data source manager")).thenReturn(Optional.of(edited));

    seeder.seed();

    assertThat(saved).noneMatch(r -> r.getName().equals("Data source manager"));
  }
}
