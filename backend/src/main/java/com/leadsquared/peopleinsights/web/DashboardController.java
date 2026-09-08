package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.AppUser;
import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.metrics.AsOfService;
import com.leadsquared.peopleinsights.metrics.CalendarService;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.DatasetLoader;
import com.leadsquared.peopleinsights.metrics.HeatMapService;
import com.leadsquared.peopleinsights.metrics.MetricsService;
import com.leadsquared.peopleinsights.repo.AppUserRepo;
import com.leadsquared.peopleinsights.repo.EmployeeRepo;
import com.leadsquared.peopleinsights.security.ScopeGuard;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The BU dashboard: metric cards, calendar, filter options and the org-wide heat map. The narrative
 * has its own controller so it can load independently of the cards.
 *
 * <p>Every handler resolves its BU through {@link ScopeGuard} before touching data, which is also what
 * writes the audit entry — a granted read and a denied cross-BU attempt are both recorded.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

  private final ScopeGuard guard;
  private final DatasetLoader loader;
  private final MetricsService metrics;
  private final CalendarService calendar;
  private final HeatMapService heatMap;
  private final EmployeeRepo employees;
  private final AppUserRepo users;
  private final AppProperties props;
  private final AsOfService asOfService;

  public DashboardController(
      ScopeGuard guard,
      DatasetLoader loader,
      MetricsService metrics,
      CalendarService calendar,
      HeatMapService heatMap,
      EmployeeRepo employees,
      AppUserRepo users,
      AppProperties props,
      AsOfService asOfService) {
    this.guard = guard;
    this.loader = loader;
    this.metrics = metrics;
    this.calendar = calendar;
    this.heatMap = heatMap;
    this.employees = employees;
    this.users = users;
    this.props = props;
    this.asOfService = asOfService;
  }

  public record Overview(
      MetricsService.Headline headline,
      CalendarService.CalendarView calendar,
      String dataAsOf,
      String confidentialityLabel,
      List<String> accessibleBusinessUnits,
      String role) {}

  /**
   * The default landing view. An HRBP with one BU gets that BU whether or not they name it.
   *
   * <p>Deliberately excludes the narrative. Generating one calls a language model, which on a cache
   * miss takes several seconds; bundling it here would hold every metric card behind it. The narrative
   * is fetched alongside this response and rendered when it arrives, so the cards — which the brief
   * requires above the fold — appear as soon as the figures exist.
   */
  @GetMapping("/overview")
  public Overview overview(
      @RequestParam(required = false) String bu, @ModelAttribute FilterQuery filters) {
    var scoped = guard.resolve(bu, "HEADCOUNT", "VIEW_OVERVIEW");
    Dataset data = loader.load(scoped, filters.toSpec());
    return new Overview(
        metrics.headline(data),
        calendar.build(data, scoped.scope().canSeeIndividualPii()),
        asOfService.dataAsOfLabel(),
        props.getConfidentialityLabel(),
        scoped.scope().allowedBus(),
        scoped.scope().role().name());
  }

  /**
   * The heat map: the same metrics with one row per business unit.
   *
   * <p>Gated on the {@code VIEW_HEATMAP} permission in {@code SecurityConfig} and scoped by the guard,
   * which is the whole control. The explicit "HR Head or CHRO" check this once carried was a second gate
   * on the tier, and once a role could be granted this view over two units it became wrong rather than
   * redundant: it refused a view that would have been correctly limited to those two rows.
   */
  @GetMapping("/heatmap")
  public HeatMapService.HeatMapView heatmap(@ModelAttribute FilterQuery filters) {
    var scoped = guard.resolve(null, "HEADCOUNT", "VIEW_HEATMAP");
    return heatMap.build(scoped, filters.toSpec());
  }

  @GetMapping("/calendar")
  public CalendarService.CalendarView calendar(
      @RequestParam(required = false) String bu, @ModelAttribute FilterQuery filters) {
    var scoped = guard.resolve(bu, "CALENDAR", "VIEW_CALENDAR");
    return calendar.build(
        loader.load(scoped, filters.toSpec()), scoped.scope().canSeeIndividualPii());
  }

  public record FilterOptions(
      List<String> businessUnits,
      List<String> grades,
      List<String> locations,
      List<String> departments,
      double tenureMaxYears,
      List<String> periods,
      String asOf) {}

  /**
   * Filter options limited to the values that exist inside the caller's own scope, narrowed further to
   * {@code bu} when one is selected — a Finance-only department has no business appearing in the
   * Engineering dropdown. The business-unit list itself is the exception: it always reflects the
   * caller's full assignment ({@code scope().allowedBus()}), never the one currently selected, since
   * that is the control used to change it.
   */
  @GetMapping("/filter-options")
  public FilterOptions filterOptions(@RequestParam(required = false) String bu) {
    var scoped = guard.resolve(bu, "HEADCOUNT", "VIEW_FILTER_OPTIONS");
    List<Employee> inScope = employees.findByVerticalIn(scoped.businessUnits());
    var asOf = asOfService.asOf();

    double maxTenure =
        inScope.stream()
            .map(e -> com.leadsquared.peopleinsights.metrics.FilterSpec.tenureYears(e, asOf))
            .filter(java.util.Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(20);

    return new FilterOptions(
        scoped.scope().allowedBus(),
        inScope.stream().map(Employee::grade).filter(java.util.Objects::nonNull).distinct().sorted().toList(),
        inScope.stream()
            .map(Employee::officeLocation)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList(),
        inScope.stream()
            .map(Employee::department)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList(),
        Math.ceil(maxTenure),
        List.of("LAST_30_DAYS", "LAST_QUARTER", "YTD", "CUSTOM"),
        asOf.toString());
  }

  /**
   * Persists the caller's filter selection so it survives a browser restart, as the brief requires.
   * Stored per user, never shared, so it cannot become a channel between sessions.
   */
  @PostMapping("/saved-filters")
  public Map<String, Object> saveFilters(@RequestBody Map<String, Object> body) {
    var scope = guard.currentScope();
    AppUser user =
        users
            .findByEmailIgnoreCase(scope.email())
            .orElseThrow(() -> new IllegalStateException("Signed-in user is not provisioned"));
    Object state = body.get("state");
    user.setSavedFilterJson(state == null ? null : String.valueOf(state));
    users.save(user);
    return Map.of("saved", true);
  }
}
