package com.leadsquared.peopleinsights.config;

import com.leadsquared.peopleinsights.domain.AppUser;
import com.leadsquared.peopleinsights.domain.CustomRole;
import com.leadsquared.peopleinsights.domain.Permission;
import com.leadsquared.peopleinsights.domain.Role;
import com.leadsquared.peopleinsights.repo.AppUserRepo;
import com.leadsquared.peopleinsights.repo.CustomRoleRepo;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Provisions the accounts the application cannot start without, and the role that opens it.
 *
 * <p>Seeding is idempotent and never overwrites an existing user — an administrator's later changes
 * survive restarts. It deliberately creates nothing else: the demonstration accounts one per tier are
 * gone, along with the tier defaults that gave them their access, so the only way anybody reaches
 * anything is a role granted on the access page.
 *
 * <p>No account is given a password, because there is no password sign-in. Identity comes from Microsoft
 * — or, while there are no Entra credentials, from the placeholder identity in {@link AppProperties}.
 */
@Component
@Order(1)
public class DataSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

  /** The one role the application maintains itself. Its name is the handle, so it is a constant. */
  private static final String FULL_ACCESS_ROLE = "Full access";

  /** A starting-point role for whoever looks after where the data comes from. */
  private static final String DATA_SOURCE_ROLE = "Data source manager";

  private final AppUserRepo users;
  private final CustomRoleRepo customRoles;
  private final AppProperties props;

  public DataSeeder(AppUserRepo users, CustomRoleRepo customRoles, AppProperties props) {
    this.users = users;
    this.customRoles = customRoles;
    this.props = props;
  }

  @Override
  public void run(ApplicationArguments args) {
    seed();
  }

  public void seed() {
    int created = 0;
    // The addresses the full-access role is granted to. Their record exists so the audit trail and the
    // access page have a name to show before they first sign in.
    created +=
        upsertUser(props.getPlaceholderSignInEmail(), props.getPlaceholderSignInName());
    created += upsertUser("hr.automation@leadsquared.com", "HR Automation");

    if (created > 0) {
      log.info("Provisioned {} dashboard user(s)", created);
    }

    seedFullAccessRole();
    seedDataSourceRole();
  }

  /**
   * Maintains the built-in full-access role.
   *
   * <p>Its permission set is rewritten to every permission on every start, on purpose: "full access"
   * has to keep meaning that after a permission is added to the enum, rather than silently becoming
   * "full access as of the release it was created in".
   *
   * <p>Membership is treated differently. The configured addresses are written when the role is created,
   * and re-added only if somebody has emptied the list — otherwise an administrator removing a member
   * would find them back after the next restart. Never leaving it empty is what guarantees the access
   * page stays reachable.
   */
  private void seedFullAccessRole() {
    CustomRole role =
        customRoles.findByNameIgnoreCase(FULL_ACCESS_ROLE).orElseGet(() -> new CustomRole(
            FULL_ACCESS_ROLE,
            "Every permission, every business unit. Maintained by the application: it cannot be "
                + "deleted, and it always keeps the permission that grants access.",
            List.of()));

    boolean creating = role.isNew();
    role.setPermissions(Arrays.asList(Permission.values()));
    role.setAllBusinessUnits(true);
    role.setBusinessUnits(List.of());
    role.setSystemRole(true);

    if (creating || role.getMemberEmails().isEmpty()) {
      var members = new LinkedHashSet<>(role.getMemberEmails());
      members.addAll(props.getFullAccessEmails());
      role.setMemberEmails(List.copyOf(members));
    }
    if (creating) {
      role.setCreatedBy("seed");
    }
    role.setUpdatedBy("seed");
    customRoles.save(role);

    if (creating) {
      log.info(
          "Seeded the \"{}\" role with {} permissions, granted to {}",
          FULL_ACCESS_ROLE,
          Permission.values().length,
          String.join(", ", role.getMemberEmails()));
    }
  }

  /**
   * A role that opens the data-source screen and nothing else.
   *
   * <p>Seeded with no members, because a role grants nothing until somebody is named in it — so this is a
   * shape ready to be granted rather than access handed out at startup. It is an ordinary role, not a
   * system one: rename it, re-scope it or delete it. Deleting it means an empty copy returns at the next
   * start, which is harmless for the same reason — nobody holds it.
   *
   * <p>It carries {@code MANAGE_CONFIG} alone. That is the separation worth keeping: looking after where
   * the figures come from — the ingest, an import, a polled feed — does not require reading anybody's
   * employee data, and the scope guard refuses this role every business unit precisely because it holds no
   * data view. {@code VIEW_AUDIT} is deliberately not included either: the audit trail records who read
   * which employee data, which is not something a data-source job needs.
   */
  private void seedDataSourceRole() {
    if (customRoles.findByNameIgnoreCase(DATA_SOURCE_ROLE).isPresent()) {
      return;
    }
    CustomRole role =
        new CustomRole(
            DATA_SOURCE_ROLE,
            "Opens the data-source screen: the workbook ingest, file imports, polled API sources and "
                + "approved headcount. No employee data.",
            List.of(Permission.MANAGE_CONFIG));
    role.setAllBusinessUnits(false);
    role.setBusinessUnits(List.of());
    role.setMemberEmails(List.of());
    role.setCreatedBy("seed");
    role.setUpdatedBy("seed");
    customRoles.save(role);
    log.info(
        "Seeded the \"{}\" role with the Administration permission and no members — grant it on /access",
        DATA_SOURCE_ROLE);
  }

  private int upsertUser(String email, String name) {
    if (users.findByEmailIgnoreCase(email).isPresent()) {
      return 0;
    }
    // The role field is a label carried into the audit trail; it grants nothing on its own.
    users.save(new AppUser(email, name, Role.HR_HEAD, List.of()));
    return 1;
  }
}
