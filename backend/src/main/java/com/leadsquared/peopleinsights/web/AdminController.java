package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.IngestRun;
import com.leadsquared.peopleinsights.domain.OpenPosition;
import com.leadsquared.peopleinsights.ingest.IngestService;
import com.leadsquared.peopleinsights.repo.IngestRunRepo;
import com.leadsquared.peopleinsights.repo.OpenPositionRepo;
import com.leadsquared.peopleinsights.repo.Store;
import com.leadsquared.peopleinsights.security.AuditService;
import com.leadsquared.peopleinsights.security.ScopeGuard;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The data-source surface: approved headcount, the state of the last ingest, and re-running it.
 *
 * <p>Reachable with {@code MANAGE_CONFIG}, enforced on the URL prefix. What used to live here — the
 * BU-to-HRBP mapping and user records — moved to the access page, where a role now decides both what a
 * person may open and which business units they may read; keeping a second screen that edited the same
 * thing differently would mean two answers to one question.
 *
 * <p>Nothing here joins to the employee master, so a configuration permission cannot become a route to
 * PII.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  private final OpenPositionRepo openPositions;
  private final IngestService ingest;
  private final IngestRunRepo ingestRuns;
  private final AuditService audit;
  private final AppProperties props;
  private final ScopeGuard guard;
  private final Store store;

  public AdminController(
      OpenPositionRepo openPositions,
      IngestService ingest,
      IngestRunRepo ingestRuns,
      AuditService audit,
      AppProperties props,
      ScopeGuard guard,
      Store store) {
    this.openPositions = openPositions;
    this.ingest = ingest;
    this.ingestRuns = ingestRuns;
    this.audit = audit;
    this.props = props;
    this.guard = guard;
    this.store = store;
  }

  // ---------------------------------------------------------------- BU mapping

  public record OpenPositionRequest(
      String requisitionId,
      String vertical,
      String department,
      String designation,
      String grade,
      String location,
      Integer approvedCount,
      Integer filledCount,
      LocalDate approvedOn,
      LocalDate targetCloseDate,
      String status) {}

  @GetMapping("/open-positions")
  public List<OpenPosition> listOpenPositions() {
    auditConfig("VIEW_OPEN_POSITIONS", null);
    return openPositions.findAll();
  }

  /**
   * Maintains approved headcount until an ATS feed exists. This is the only way the open-positions
   * metric card becomes populated; it is intentionally not derived from anything in the extract.
   */
  @PostMapping("/open-positions")
  public OpenPosition upsertOpenPosition(@RequestBody OpenPositionRequest request) {
    var scope = guard.currentScope();
    if (request == null || request.vertical() == null || request.approvedCount() == null) {
      throw new IllegalArgumentException("A business unit and approved count are required.");
    }
    String canonical =
        props.getBusinessUnits().stream()
            .filter(b -> b.equalsIgnoreCase(request.vertical()))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("Unrecognised business unit: " + request.vertical()));

    OpenPosition position =
        new OpenPosition(
            UUID.randomUUID().toString(),
            request.requisitionId(),
            canonical,
            request.department(),
            request.designation(),
            request.grade(),
            request.location(),
            request.approvedCount(),
            request.filledCount() == null ? 0 : request.filledCount(),
            request.approvedOn(),
            request.targetCloseDate(),
            request.status() == null ? "OPEN" : request.status().toUpperCase(),
            scope.email(),
            Instant.now());
    OpenPosition saved = store.insert(position);
    auditConfig("CREATE_OPEN_POSITION", canonical + " x" + request.approvedCount());
    return saved;
  }

  @DeleteMapping("/open-positions/{id}")
  public Map<String, Object> deleteOpenPosition(@PathVariable String id) {
    openPositions.deleteById(id);
    auditConfig("DELETE_OPEN_POSITION", id);
    return Map.of("deleted", true);
  }

  // ---------------------------------------------------------------- data refresh

  @GetMapping("/data-status")
  public Map<String, Object> dataStatus() {
    var last = ingestRuns.findFirstByStatusOrderByFinishedAtDesc("SUCCESS");
    return Map.of(
        "lastRun", last.map(IngestRun::finishedAt).map(Instant::toString).orElse("never"),
        "dataAsOf", last.map(IngestRun::dataAsOfDate).orElse("unknown"),
        "counts", last.map(IngestRun::counts).orElse(Map.of()),
        "warnings", last.map(IngestRun::warnings).orElse(List.of()),
        "sourceDirectory", last.map(IngestRun::sourceDirectory).orElse("unknown"),
        "asOfOverride", String.valueOf(props.getAsOfDate()));
  }

  /** Re-reads the workbooks. Synchronous and slow by design — it is an explicit admin action. */
  @PostMapping("/refresh-data")
  public IngestRun refreshData() {
    auditConfig("REFRESH_DATA", "Workbook re-ingest triggered");
    return ingest.run();
  }

  private void auditConfig(String action, String detail) {
    var scope = guard.currentScope();
    audit.granted(scope, null, "CONFIG", action, detail);
  }
}
