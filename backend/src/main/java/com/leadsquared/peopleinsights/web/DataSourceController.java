package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.datasource.ApiSourceService;
import com.leadsquared.peopleinsights.datasource.ImportService;
import com.leadsquared.peopleinsights.datasource.TableCatalog;
import com.leadsquared.peopleinsights.domain.ApiSource;
import com.leadsquared.peopleinsights.repo.ApiSourceRepo;
import com.leadsquared.peopleinsights.security.AuditService;
import com.leadsquared.peopleinsights.security.ScopeGuard;
import java.io.IOException;
import java.time.Instant;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * Where data comes from: file imports and polled API endpoints.
 *
 * <p>Behind {@code MANAGE_CONFIG}, enforced on the URL prefix in {@code SecurityConfig}. Every write is
 * audited under the {@code DATA_SOURCE} type — an import changes what every view reports, and a source
 * configured here keeps writing long after whoever configured it has gone home, so both belong in the
 * record beside the reads.
 */
@RestController
@RequestMapping("/api/data-source")
public class DataSourceController {

  private final TableCatalog catalog;
  private final ImportService imports;
  private final ApiSourceService apis;
  private final ApiSourceRepo sources;
  private final ScopeGuard guard;
  private final AuditService audit;

  public DataSourceController(
      TableCatalog catalog,
      ImportService imports,
      ApiSourceService apis,
      ApiSourceRepo sources,
      ScopeGuard guard,
      AuditService audit) {
    this.catalog = catalog;
    this.imports = imports;
    this.apis = apis;
    this.sources = sources;
    this.guard = guard;
    this.audit = audit;
  }

  // ---------------------------------------------------------------- tables

  /** The tables an import may write to, with their columns and current row counts. */
  @GetMapping("/tables")
  public List<TableCatalog.TableInfo> tables() {
    return catalog.importableTables();
  }

  // ---------------------------------------------------------------- import

  /**
   * Loads a CSV or .xlsx into one table.
   *
   * @param mode {@code APPEND} to add to what is there, {@code REPLACE} to empty the table first
   */
  @PostMapping("/import")
  public ImportService.Report importFile(
      @RequestParam("file") MultipartFile file,
      @RequestParam("table") String table,
      @RequestParam(name = "mode", defaultValue = "APPEND") String mode)
      throws IOException {

    var scope = guard.currentScope();
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("Choose a file to import.");
    }
    ImportService.Mode parsed;
    try {
      parsed = ImportService.Mode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Mode must be APPEND or REPLACE.");
    }

    ImportService.Report report;
    try (var in = file.getInputStream()) {
      report = imports.importFile(file.getOriginalFilename(), in, table, parsed);
    }

    audit.event(
        scope.email(),
        scope.role(),
        null,
        "DATA_SOURCE",
        parsed == ImportService.Mode.REPLACE ? "IMPORT_REPLACE" : "IMPORT_APPEND",
        "GRANTED",
        file.getOriginalFilename()
            + " -> "
            + report.table()
            + ": "
            + report.rowsWritten()
            + " written, "
            + report.rowsRejected()
            + " rejected"
            + (report.deletedFirst() > 0 ? ", " + report.deletedFirst() + " replaced" : ""));
    return report;
  }

  // ---------------------------------------------------------------- api sources

  /**
   * @param rowsHeld records currently landed for this source
   */
  public record SourceView(ApiSource source, long rowsHeld, Map<String, String> headers) {}

  @GetMapping("/api-sources")
  public List<SourceView> listSources() {
    return sources.findAll().stream()
        .sorted(java.util.Comparator.comparing(ApiSource::getName, String.CASE_INSENSITIVE_ORDER))
        .map(s -> new SourceView(s, apis.rowCount(s.getId()), apis.headerMap(s.getHeadersJson())))
        .toList();
  }

  public record SourceRequest(
      String id,
      String name,
      String url,
      String headersJson,
      String jsonPath,
      String keyField,
      Integer intervalSeconds,
      Boolean enabled,
      Boolean replaceEachRun) {}

  /** Creates or replaces a source. The URL and the headers are validated before it is stored. */
  @PostMapping("/api-sources")
  public SourceView saveSource(@RequestBody SourceRequest request) {
    var scope = guard.currentScope();
    if (request == null || request.name() == null || request.name().isBlank()) {
      throw new IllegalArgumentException("A source needs a name.");
    }

    String name = request.name().trim();
    // Validated now rather than at the first poll, so a bad URL or malformed headers fail in front of the
    // person who typed them.
    apis.validated(request.url());
    apis.headerMap(request.headersJson());

    ApiSource source =
        request.id() == null || request.id().isBlank()
            ? new ApiSource()
            : sources
                .findById(request.id())
                .orElseThrow(() -> new IllegalArgumentException("No source with id " + request.id()));

    Optional<ApiSource> sameName = sources.findByNameIgnoreCase(name);
    if (sameName.isPresent() && !sameName.get().getId().equals(source.getId())) {
      throw new IllegalArgumentException("A source called \"" + name + "\" already exists.");
    }

    boolean creating = source.isNew();
    source.setName(name);
    source.setUrl(request.url().trim());
    source.setHeadersJson(blankToNull(request.headersJson()));
    source.setJsonPath(blankToNull(request.jsonPath()));
    source.setKeyField(blankToNull(request.keyField()));
    source.setIntervalSeconds(
        request.intervalSeconds() == null ? 300 : Math.max(10, request.intervalSeconds()));
    source.setEnabled(request.enabled() == null || request.enabled());
    source.setReplaceEachRun(request.replaceEachRun() == null || request.replaceEachRun());
    source.setUpdatedBy(scope.email());
    source.setUpdatedAt(Instant.now());
    if (creating) {
      source.setCreatedBy(scope.email());
      source.setCreatedAt(Instant.now());
    }

    ApiSource saved = sources.save(source);
    audit.event(
        scope.email(),
        scope.role(),
        null,
        "DATA_SOURCE",
        creating ? "CREATE_API_SOURCE" : "UPDATE_API_SOURCE",
        "GRANTED",
        name + " -> " + saved.getUrl() + " every " + saved.getIntervalSeconds() + "s");
    return new SourceView(saved, apis.rowCount(saved.getId()), apis.headerMap(saved.getHeadersJson()));
  }

  @DeleteMapping("/api-sources/{id}")
  public Map<String, Object> deleteSource(@PathVariable String id) {
    var scope = guard.currentScope();
    ApiSource source =
        sources.findById(id).orElseThrow(() -> new IllegalArgumentException("No source with id " + id));
    sources.delete(source);
    audit.event(
        scope.email(), scope.role(), null, "DATA_SOURCE", "DELETE_API_SOURCE", "GRANTED", source.getName());
    return Map.of("deleted", true, "name", source.getName());
  }

  public record TestRequest(String url, String headersJson, String jsonPath) {}

  /** Calls an endpoint and reports what came back. Stores nothing, so it is safe to press repeatedly. */
  @PostMapping("/api-sources/test")
  public ApiSourceService.TestResult testSource(@RequestBody TestRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("A URL is required.");
    }
    return apis.test(request.url(), request.headersJson(), request.jsonPath());
  }

  /** Runs a stored source now, without waiting for its interval. */
  @PostMapping("/api-sources/{id}/run")
  public ApiSourceService.RunResult runNow(@PathVariable String id) {
    var scope = guard.currentScope();
    ApiSource source =
        sources.findById(id).orElseThrow(() -> new IllegalArgumentException("No source with id " + id));
    ApiSourceService.RunResult result = apis.run(source);
    audit.event(
        scope.email(),
        scope.role(),
        null,
        "DATA_SOURCE",
        "RUN_API_SOURCE",
        result.ok() ? "GRANTED" : "DENIED",
        source.getName() + ": " + result.message());
    return result;
  }

  /** The most recent records landed for a source, so the screen can show what arrived. */
  @GetMapping("/api-sources/{id}/rows")
  public List<Map<String, Object>> rows(
      @PathVariable String id, @RequestParam(name = "limit", defaultValue = "10") int limit) {
    return apis.recentRows(id, limit);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
