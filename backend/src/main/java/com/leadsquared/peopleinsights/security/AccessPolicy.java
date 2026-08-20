package com.leadsquared.peopleinsights.security;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.CustomRole;
import com.leadsquared.peopleinsights.domain.Permission;
import com.leadsquared.peopleinsights.domain.Role;
import com.leadsquared.peopleinsights.repo.CustomRoleRepo;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns an identity into a concrete permission set and business-unit list.
 *
 * <p>One source: the roles naming the signed-in address, unioned. An address no role names resolves to
 * nothing at all — no permission, no business unit — so access is granted deliberately or not at all.
 * There is no tier fallback: the {@link Role} on a user record is a label carried into the audit trail
 * and grants nothing on its own, which means the access page is the only place access comes from.
 *
 * <p>Resolution happens per request rather than at sign-in. An administrator who removes a permission
 * expects it gone now, not at the offender's next sign-in, and a session that outlives its grant is the
 * kind of gap an audit finds later. The roles table is small and changes rarely, so it is held in memory
 * and re-read when {@link #invalidate()} is called by the controller that writes it.
 */
@Service
public class AccessPolicy {

  private static final Logger log = LoggerFactory.getLogger(AccessPolicy.class);

  private final CustomRoleRepo roles;
  private final AppProperties props;

  /** Null means "not loaded"; the reference is replaced wholesale so readers never see a partial list. */
  private final AtomicReference<List<CustomRole>> cache = new AtomicReference<>(null);

  public AccessPolicy(CustomRoleRepo roles, AppProperties props) {
    this.roles = roles;
    this.props = props;
  }

  /**
   * @param permissions everything the caller may do
   * @param businessUnits the only BU values that may reach a query
   * @param sourceRoles names of the custom roles that decided this, empty when the tier default did
   */
  public record EffectiveAccess(
      Set<Permission> permissions, List<String> businessUnits, List<String> sourceRoles) {

    public boolean has(Permission permission) {
      return permissions.contains(permission);
    }
  }

  /**
   * Resolves what this user may reach.
   *
   * <p>{@code tier} and {@code assignedBus} are accepted so callers need not know whether they matter —
   * they do not. They are the user record's own fields, kept for the audit trail and for the BU-to-HRBP
   * mapping, and an address with no role assigned resolves to no access whether they are set or not.
   */
  public EffectiveAccess resolve(String email, Role tier, List<String> assignedBus) {
    List<CustomRole> matching = customRoles().stream().filter(r -> r.covers(email)).toList();

    if (matching.isEmpty()) {
      return new EffectiveAccess(Set.of(), List.of(), List.of());
    }

    Set<Permission> granted = EnumSet.noneOf(Permission.class);
    Set<String> bus = new LinkedHashSet<>();
    List<String> names = new ArrayList<>();
    boolean everyUnit = false;

    for (CustomRole role : matching) {
      granted.addAll(role.getPermissions());
      names.add(role.getName());
      if (role.isAllBusinessUnits()) {
        everyUnit = true;
      } else {
        bus.addAll(canonicalise(role.getBusinessUnits()));
      }
    }

    // A role granting individual data over no business unit would read as a grant and behave as a
    // denial, so the union is what it says: every unit, or the named ones.
    List<String> scope = everyUnit ? props.getBusinessUnits() : List.copyOf(bus);
    return new EffectiveAccess(Set.copyOf(granted), scope, List.copyOf(names));
  }

  /** Every role, from the in-memory copy. */
  public List<CustomRole> customRoles() {
    List<CustomRole> held = cache.get();
    if (held != null) {
      return held;
    }
    List<CustomRole> loaded = List.copyOf(roles.findAll());
    cache.set(loaded);
    return loaded;
  }

  /** Roles naming this address. */
  public List<CustomRole> rolesFor(String email) {
    return customRoles().stream().filter(r -> r.covers(email)).toList();
  }

  /** Called by whatever writes the roles table, so the next request reads the change. */
  public void invalidate() {
    cache.set(null);
    log.debug("Custom role cache dropped — the next request re-reads the table");
  }

  /** Maps request values onto the canonical BU names, discarding anything unknown. */
  private List<String> canonicalise(List<String> input) {
    if (input == null) {
      return List.of();
    }
    return input.stream()
        .map(
            value ->
                props.getBusinessUnits().stream()
                    .filter(bu -> bu.equalsIgnoreCase(value == null ? null : value.trim()))
                    .findFirst()
                    .orElse(null))
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
  }
}
