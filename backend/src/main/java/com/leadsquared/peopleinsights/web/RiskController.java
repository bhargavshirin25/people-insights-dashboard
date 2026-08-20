package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.domain.Employee;
import com.leadsquared.peopleinsights.domain.RetentionAction;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.DatasetLoader;
import com.leadsquared.peopleinsights.metrics.RiskScoringService;
import com.leadsquared.peopleinsights.repo.EmployeeRepo;
import com.leadsquared.peopleinsights.repo.RetentionActionRepo;
import com.leadsquared.peopleinsights.repo.Store;
import com.leadsquared.peopleinsights.security.AccessDeniedForBuException;
import com.leadsquared.peopleinsights.security.ScopeGuard;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The attrition risk register and the retention actions logged against it.
 *
 * <p>This is the most PII-heavy surface in the product — a ranked list of named employees judged
 * likely to leave — so it is gated on {@code resolveIndividual}, which refuses anything below HRBP even
 * when the BU itself would be readable.
 */
@RestController
@RequestMapping("/api/risk")
public class RiskController {

  private final ScopeGuard guard;
  private final DatasetLoader loader;
  private final RiskScoringService risk;
  private final RetentionActionRepo actions;
  private final EmployeeRepo employees;
  private final Store store;

  public RiskController(
      ScopeGuard guard,
      DatasetLoader loader,
      RiskScoringService risk,
      RetentionActionRepo actions,
      EmployeeRepo employees,
      Store store) {
    this.guard = guard;
    this.loader = loader;
    this.risk = risk;
    this.actions = actions;
    this.employees = employees;
    this.store = store;
  }

  public record RegisterRow(
      RiskScoringService.Assessment assessment, List<RetentionAction> loggedActions) {}

  public record RegisterView(
      String businessUnit,
      int totalActive,
      int highCount,
      int mediumCount,
      List<RegisterRow> rows,
      String methodology) {}

  @GetMapping("/register")
  public RegisterView register(
      @RequestParam(required = false) String bu, @ModelAttribute FilterQuery filters) {
    var scoped = guard.resolveIndividual(bu, "RISK_REGISTER", "VIEW_RISK_REGISTER");
    Dataset data = loader.load(scoped, filters.toSpec());

    List<RiskScoringService.Assessment> register = risk.register(data);
    Map<String, List<RetentionAction>> byEmployee =
        actions.findByEmployeeIdIn(register.stream().map(RiskScoringService.Assessment::employeeId).toList())
            .stream()
            .collect(Collectors.groupingBy(RetentionAction::employeeId));

    return new RegisterView(
        scoped.label(),
        data.activeEmployees().size(),
        (int) register.stream().filter(RiskScoringService.Assessment::isHigh).count(),
        (int) register.stream().filter(a -> "Medium".equals(a.band())).count(),
        register.stream()
            .map(a -> new RegisterRow(a, byEmployee.getOrDefault(a.employeeId(), List.of())))
            .toList(),
        "Additive rule model over PMS trend, eNPS, promotion history, attendance, leave pattern and "
            + "compa ratio. Every factor is visible on this view and traceable to source data.");
  }

  public record LogActionRequest(String actionType, LocalDate actionDate, String notes) {}

  /**
   * Logs a retention action. The employee's own BU is re-checked against the caller's assignment, so a
   * known employee id from another BU cannot be written to.
   */
  @PostMapping("/actions/{employeeId}")
  public RetentionAction logAction(
      @PathVariable String employeeId, @RequestBody LogActionRequest request) {
    var scope = guard.currentScope();
    Employee employee =
        employees
            .findById(employeeId)
            .orElseThrow(() -> new IllegalArgumentException("No employee with id " + employeeId));
    guard.assertEmployeeInScope(scope, employee.vertical(), "RISK_REGISTER", "LOG_RETENTION_ACTION");

    if (request == null || request.actionType() == null || request.actionType().isBlank()) {
      throw new IllegalArgumentException("An action type is required.");
    }

    return store.insert(
        new RetentionAction(
            UUID.randomUUID().toString(),
            employeeId,
            employee.vertical(),
            request.actionType(),
            request.actionDate() == null ? LocalDate.now() : request.actionDate(),
            request.notes(),
            scope.email(),
            scope.displayName(),
            Instant.now()));
  }

  /** Every action logged in scope. HR Head and CHRO see all BUs; an HRBP sees their own. */
  @GetMapping("/actions")
  public List<RetentionAction> actionLog(@RequestParam(required = false) String bu) {
    var scoped = guard.resolveIndividual(bu, "RISK_REGISTER", "VIEW_ACTION_LOG");
    return actions.findByVerticalInOrderByLoggedAtDesc(scoped.businessUnits());
  }

  public record RiskDetail(
      RiskScoringService.Assessment assessment, List<RetentionAction> actions) {}

  /** Full factor breakdown for one employee. */
  @GetMapping("/detail/{employeeId}")
  public RiskDetail detail(@PathVariable String employeeId, @ModelAttribute FilterQuery filters) {
    var scope = guard.currentScope();
    Employee employee =
        employees
            .findById(employeeId)
            .orElseThrow(() -> new IllegalArgumentException("No employee with id " + employeeId));
    guard.assertEmployeeInScope(scope, employee.vertical(), "RISK_REGISTER", "VIEW_RISK_DETAIL");

    if (!employee.isActive()) {
      throw new AccessDeniedForBuException(
          employee.vertical(), "Risk scoring applies to active employees only.");
    }

    // Load the employee's own BU so the assessment uses the same joined data as the register.
    var scoped = new ScopeGuard.Scoped(scope, List.of(employee.vertical()), employee.vertical());
    Dataset data = loader.load(scoped, filters.toSpec());

    return new RiskDetail(
        risk.assessOne(employee, data), actions.findByEmployeeIdOrderByLoggedAtDesc(employeeId));
  }
}
