package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.AppUser;
import com.leadsquared.peopleinsights.domain.CustomRole;
import com.leadsquared.peopleinsights.domain.Permission;
import com.leadsquared.peopleinsights.domain.Role;
import com.leadsquared.peopleinsights.repo.AppUserRepo;
import com.leadsquared.peopleinsights.repo.CustomRoleRepo;
import com.leadsquared.peopleinsights.security.AccessPolicy;
import com.leadsquared.peopleinsights.security.AuditService;
import com.leadsquared.peopleinsights.security.ScopeGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Custom roles: what they may reach, and whose addresses they apply to.
 *
 * <p>Reachable only with {@code MANAGE_ACCESS}, enforced in {@code SecurityConfig} on the URL prefix.
 * Every write is audited under the {@code ACCESS} data type, because a change here changes what somebody
 * else can read — which makes it more sensitive than any single data read on the dashboard, not less.
 *
 * <p>Two invariants are held here rather than trusted to the screen. A role can never be saved with no
 * scope while granting a data view, since that grant would read as access and behave as a denial; and
 * the seeded system role can neither be deleted nor stripped of {@code MANAGE_ACCESS}, so there is no
 * sequence of clicks that leaves nobody able to grant access back.
 */
@RestController
@RequestMapping("/api/access")
public class AccessController {

  private final CustomRoleRepo roles;
  private final AppUserRepo users;
  private final AccessPolicy policy;
  private final ScopeGuard guard;
  private final AuditService audit;
  private final AppProperties props;

  public AccessController(
      CustomRoleRepo roles,
      AppUserRepo users,
      AccessPolicy policy,
      ScopeGuard guard,
      AuditService audit,
      AppProperties props) {
    this.roles = roles;
    this.users = users;
    this.policy = policy;
    this.guard = guard;
    this.audit = audit;
    this.props = props;
  }

  // ---------------------------------------------------------------- catalogue

  /**
   * @param key the enum name, which is what a role stores
   */
  public record PermissionInfo(String key, String label, String description, String group) {}

  public record GroupInfo(String key, String label, String description) {}

  public record Catalog(
      List<GroupInfo> groups, List<PermissionInfo> permissions, List<String> businessUnits) {}

  /**
   * Everything the access screen needs to draw itself: the checkbox catalogue and the business units.
   *
   * <p>Served rather than hard-coded in the frontend so a permission added to the enforcement layer
   * appears on the screen that grants it, instead of being enforceable but ungrantable.
   */
  @GetMapping("/catalog")
  public Catalog catalog() {
    List<GroupInfo> groups =
        java.util.Arrays.stream(Permission.Group.values())
            .map(g -> new GroupInfo(g.name(), g.label(), g.description()))
            .toList();
    List<PermissionInfo> permissions =
        java.util.Arrays.stream(Permission.values())
            .map(p -> new PermissionInfo(p.name(), p.label(), p.description(), p.group().name()))
            .toList();
    return new Catalog(groups, permissions, props.getBusinessUnits());
  }

  // ---------------------------------------------------------------- roles

  public record RoleView(
      String id,
      String name,
      String description,
      List<String> permissions,
      List<String> businessUnits,
      boolean allBusinessUnits,
      List<String> memberEmails,
      boolean systemRole,
      String updatedBy,
      Instant updatedAt) {

    static RoleView of(CustomRole role) {
      return new RoleView(
          role.getId(),
          role.getName(),
          role.getDescription(),
          role.getPermissions().stream().map(Enum::name).toList(),
          role.getBusinessUnits(),
          role.isAllBusinessUnits(),
          role.getMemberEmails(),
          role.isSystemRole(),
          role.getUpdatedBy(),
          role.getUpdatedAt());
    }
  }

  @GetMapping("/roles")
  public List<RoleView> listRoles() {
    return roles.findAll().stream()
        .sorted(
            Comparator.comparing(CustomRole::isSystemRole)
                .reversed()
                .thenComparing(CustomRole::getName, String.CASE_INSENSITIVE_ORDER))
        .map(RoleView::of)
        .toList();
  }

  public record RoleRequest(
      String id,
      String name,
      String description,
      List<String> permissions,
      List<String> businessUnits,
      boolean allBusinessUnits,
      List<String> memberEmails) {}

  /** Creates a role, or replaces one when {@code id} is given. */
  @PostMapping("/roles")
  public RoleView saveRole(@RequestBody RoleRequest request) {
    var scope = guard.currentScope();
    if (request == null || request.name() == null || request.name().isBlank()) {
      throw new IllegalArgumentException("A role needs a name.");
    }

    String name = request.name().trim();
    List<Permission> permissions = parsePermissions(request.permissions());
    boolean allUnits = request.allBusinessUnits();
    List<String> units = canonicalise(request.businessUnits());

    if (permissions.isEmpty()) {
      throw new IllegalArgumentException("A role needs at least one permission.");
    }
    // A data view over no business unit is a grant that cannot return a row. Refusing it here means the
    // screen cannot produce a role whose behaviour contradicts what it says.
    boolean grantsDataView = permissions.stream().anyMatch(p -> Permission.dataViews().contains(p));
    if (grantsDataView && !allUnits && units.isEmpty()) {
      throw new IllegalArgumentException(
          "This role opens a data view, so it needs at least one business unit — or every unit.");
    }

    CustomRole role =
        request.id() == null || request.id().isBlank()
            ? new CustomRole()
            : roles
                .findById(request.id())
                .orElseThrow(() -> new IllegalArgumentException("No role with id " + request.id()));

    Optional<CustomRole> sameName = roles.findByNameIgnoreCase(name);
    if (sameName.isPresent() && !sameName.get().getId().equals(role.getId())) {
      throw new IllegalArgumentException("A role called \"" + name + "\" already exists.");
    }

    if (role.isSystemRole()) {
      // The full-access role is what guarantees somebody can always reach this screen.
      if (!permissions.contains(Permission.MANAGE_ACCESS)) {
        throw new IllegalArgumentException(
            "The built-in full-access role must keep the \"Manage access\" permission.");
      }
      if (!allUnits) {
        throw new IllegalArgumentException(
            "The built-in full-access role covers every business unit and cannot be narrowed.");
      }
    }

    boolean creating = role.isNew();
    role.setName(name);
    role.setDescription(request.description() == null ? null : request.description().trim());
    role.setPermissions(permissions);
    role.setAllBusinessUnits(allUnits);
    role.setBusinessUnits(allUnits ? List.of() : units);
    if (request.memberEmails() != null) {
      role.setMemberEmails(request.memberEmails());
    }
    role.setUpdatedBy(scope.email());
    role.setUpdatedAt(Instant.now());
    if (creating) {
      role.setCreatedBy(scope.email());
      role.setCreatedAt(Instant.now());
    }

    CustomRole saved = roles.save(role);
    policy.invalidate();
    audit.event(
        scope.email(),
        scope.role(),
        null,
        "ACCESS",
        creating ? "CREATE_ROLE" : "UPDATE_ROLE",
        "GRANTED",
        name
            + " — "
            + permissions.size()
            + " permissions, "
            + (allUnits ? "all business units" : units.size() + " business units")
            + ", "
            + saved.getMemberEmails().size()
            + " members");
    return RoleView.of(saved);
  }

  @DeleteMapping("/roles/{id}")
  public Map<String, Object> deleteRole(@PathVariable String id) {
    var scope = guard.currentScope();
    CustomRole role =
        roles.findById(id).orElseThrow(() -> new IllegalArgumentException("No role with id " + id));
    if (role.isSystemRole()) {
      throw new IllegalArgumentException(
          "The built-in full-access role cannot be deleted. Remove its members instead.");
    }
    roles.delete(role);
    policy.invalidate();
    audit.event(
        scope.email(), scope.role(), null, "ACCESS", "DELETE_ROLE", "GRANTED", role.getName());
    return Map.of("deleted", true, "name", role.getName());
  }

  // ---------------------------------------------------------------- membership

  public record MemberRequest(String email) {}

  /** Adds an address to a role. The address need not exist as a user yet. */
  @PostMapping("/roles/{id}/members")
  public RoleView addMember(@PathVariable String id, @RequestBody MemberRequest request) {
    var scope = guard.currentScope();
    if (request == null || request.email() == null || request.email().isBlank()) {
      throw new IllegalArgumentException("An email address is required.");
    }
    String email = request.email().trim().toLowerCase();
    if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
      throw new IllegalArgumentException("\"" + request.email() + "\" is not an email address.");
    }

    CustomRole role =
        roles.findById(id).orElseThrow(() -> new IllegalArgumentException("No role with id " + id));
    var members = new LinkedHashSet<>(role.getMemberEmails());
    members.add(email);
    role.setMemberEmails(List.copyOf(members));
    role.setUpdatedBy(scope.email());
    role.setUpdatedAt(Instant.now());
    CustomRole saved = roles.save(role);
    policy.invalidate();
    audit.event(
        scope.email(),
        scope.role(),
        null,
        "ACCESS",
        "GRANT_ROLE",
        "GRANTED",
        email + " added to " + role.getName());
    return RoleView.of(saved);
  }

  @DeleteMapping("/roles/{id}/members")
  public RoleView removeMember(@PathVariable String id, @RequestParam String email) {
    var scope = guard.currentScope();
    CustomRole role =
        roles.findById(id).orElseThrow(() -> new IllegalArgumentException("No role with id " + id));
    String wanted = email == null ? "" : email.trim();
    List<String> remaining =
        role.getMemberEmails().stream().filter(m -> !m.equalsIgnoreCase(wanted)).toList();
    role.setMemberEmails(remaining);
    role.setUpdatedBy(scope.email());
    role.setUpdatedAt(Instant.now());
    CustomRole saved = roles.save(role);
    policy.invalidate();
    audit.event(
        scope.email(),
        scope.role(),
        null,
        "ACCESS",
        "REVOKE_ROLE",
        "GRANTED",
        wanted + " removed from " + role.getName());
    return RoleView.of(saved);
  }

  // ---------------------------------------------------------------- who has what

  public record PersonAccess(
      String email,
      String displayName,
      List<String> customRoles,
      List<String> permissions,
      List<String> businessUnits,
      boolean allBusinessUnits,
      boolean provisioned,
      Instant lastLoginAt) {}

  /**
   * Every address the application knows about, with the access it resolves to.
   *
   * <p>Deliberately the union of two lists. A user row exists once somebody signs in or is provisioned;
   * a role's member list can name a colleague who has not arrived yet. Showing only the first would hide
   * a grant that is already live, which is the opposite of what this screen is for.
   */
  @GetMapping("/people")
  public List<PersonAccess> people() {
    var byEmail = new java.util.LinkedHashMap<String, PersonAccess>();

    for (AppUser user : users.findAll()) {
      byEmail.put(user.getEmail().toLowerCase(), describe(user.getEmail(), user));
    }
    for (CustomRole role : roles.findAll()) {
      for (String member : role.getMemberEmails()) {
        byEmail.computeIfAbsent(member.toLowerCase(), email -> describe(email, null));
      }
    }
    return byEmail.values().stream()
        .sorted(
            Comparator.comparing(PersonAccess::provisioned)
                .reversed()
                .thenComparing(PersonAccess::email, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private PersonAccess describe(String email, AppUser user) {
    Role tier = user == null ? null : user.getRole();
    var effective = policy.resolve(email, tier, user == null ? List.of() : user.getAssignedBus());
    List<String> roleNames = policy.rolesFor(email).stream().map(CustomRole::getName).toList();
    boolean allUnits = effective.businessUnits().size() >= props.getBusinessUnits().size();
    return new PersonAccess(
        email,
        user == null ? null : user.getDisplayName(),
        roleNames,
        effective.permissions().stream().map(Enum::name).sorted().toList(),
        effective.businessUnits(),
        allUnits,
        user != null,
        user == null ? null : user.getLastLoginAt());
  }

  // ---------------------------------------------------------------- helpers

  private List<Permission> parsePermissions(List<String> keys) {
    if (keys == null) {
      return List.of();
    }
    List<Permission> out = new ArrayList<>();
    for (String key : keys) {
      Permission.of(key).ifPresent(out::add);
    }
    return out.stream().distinct().toList();
  }

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
