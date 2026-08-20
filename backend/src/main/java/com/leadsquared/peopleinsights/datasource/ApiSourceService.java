package com.leadsquared.peopleinsights.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadsquared.peopleinsights.config.DataSourceProperties;
import com.leadsquared.peopleinsights.domain.ApiSource;
import com.leadsquared.peopleinsights.repo.ApiSourceRepo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Polls configured GET endpoints and lands what they return in {@code api_feed_rows}.
 *
 * <p>Three things are fixed rather than configurable, and each is a safety property rather than a
 * simplification. The method is GET, so a source can only pull. The response is capped in bytes and in
 * records, so a misconfigured endpoint cannot exhaust the heap or the table. And the request carries no
 * credential of the application's own — only headers an administrator typed — so a source cannot borrow
 * this application's identity to reach something else.
 *
 * <p>A configured URL is fetched by the server, which is a request an administrator can point anywhere the
 * server can reach, including inside the network. That is inherent to the feature and the reason it sits
 * behind {@code MANAGE_CONFIG}; {@code dashboard.data-source.allow-private-hosts} exists to turn it off in
 * an environment where that is not acceptable.
 */
@Service
public class ApiSourceService {

  private static final Logger log = LoggerFactory.getLogger(ApiSourceService.class);

  private final ApiSourceRepo sources;
  private final JdbcTemplate jdbc;
  private final DataSourceProperties props;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http;

  public ApiSourceService(ApiSourceRepo sources, JdbcTemplate jdbc, DataSourceProperties props) {
    this.sources = sources;
    this.jdbc = jdbc;
    this.props = props;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // A redirect to a different host would sidestep the checks the URL was given, so follow none.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  // ---------------------------------------------------------------- test

  /**
   * @param records how many records were found at {@code jsonPath}
   * @param preview the first record, as JSON, truncated
   * @param fields the field names of the first record, so the key field can be chosen from a list
   */
  public record TestResult(
      boolean ok,
      int statusCode,
      long elapsedMillis,
      String contentType,
      long bytes,
      int records,
      String shape,
      List<String> fields,
      String preview,
      String message) {}

  /** Calls the endpoint and reports what came back. Writes nothing. */
  public TestResult test(String url, String headersJson, String jsonPath) {
    long started = System.nanoTime();
    try {
      URI uri = validated(url);
      HttpResponse<String> response = fetch(uri, headersJson);
      long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
      String body = response.body() == null ? "" : response.body();

      if (response.statusCode() / 100 != 2) {
        return new TestResult(
            false,
            response.statusCode(),
            elapsed,
            contentType(response),
            body.length(),
            0,
            "—",
            List.of(),
            truncate(body, 400),
            "The endpoint answered " + response.statusCode() + ".");
      }

      JsonNode root = mapper.readTree(body);
      JsonNode at = atPath(root, jsonPath);
      if (at == null || at.isMissingNode() || at.isNull()) {
        return new TestResult(
            false,
            response.statusCode(),
            elapsed,
            contentType(response),
            body.length(),
            0,
            root.getNodeType().toString().toLowerCase(Locale.ROOT),
            List.of(),
            truncate(body, 400),
            "Nothing found at path \""
                + (jsonPath == null || jsonPath.isBlank() ? "(root)" : jsonPath)
                + "\". The response's top-level fields are: "
                + fieldNames(root));
      }

      List<JsonNode> records = records(at);
      JsonNode first = records.isEmpty() ? null : records.get(0);
      return new TestResult(
          true,
          response.statusCode(),
          elapsed,
          contentType(response),
          body.length(),
          records.size(),
          at.isArray() ? "array" : at.getNodeType().toString().toLowerCase(Locale.ROOT),
          first == null ? List.of() : fieldList(first),
          first == null ? "" : truncate(first.toPrettyString(), 900),
          records.isEmpty()
              ? "Reached the endpoint, but there are no records at that path."
              : "Reached the endpoint and found " + records.size() + " record(s).");

    } catch (IllegalArgumentException bad) {
      return failedTest(started, bad.getMessage());
    } catch (Exception e) {
      return failedTest(started, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private TestResult failedTest(long started, String message) {
    return new TestResult(
        false,
        0,
        Duration.ofNanos(System.nanoTime() - started).toMillis(),
        null,
        0,
        0,
        "—",
        List.of(),
        "",
        message);
  }

  // ---------------------------------------------------------------- run

  /**
   * @param rows rows written
   */
  public record RunResult(boolean ok, int rows, String message, Instant at) {}

  /**
   * Fetches one source and writes its records, then records the outcome on the source itself.
   *
   * <p>A failure is stored rather than thrown: the poller must keep going, and the person who configured
   * the source needs to see what happened without reading a log. That is also why the timestamp is written
   * on failure — a source that fails is still due again an interval later, not immediately.
   */
  public RunResult run(ApiSource source) {
    Instant now = Instant.now();
    try {
      URI uri = validated(source.getUrl());
      HttpResponse<String> response = fetch(uri, source.getHeadersJson());
      if (response.statusCode() / 100 != 2) {
        return record(source, false, 0, "The endpoint answered " + response.statusCode() + ".", now);
      }

      JsonNode at = atPath(mapper.readTree(response.body() == null ? "null" : response.body()), source.getJsonPath());
      if (at == null || at.isMissingNode() || at.isNull()) {
        return record(source, false, 0, "Nothing found at the configured path.", now);
      }

      List<JsonNode> records = records(at);
      if (records.size() > props.getMaxRecordsPerRun()) {
        records = records.subList(0, props.getMaxRecordsPerRun());
      }

      if (source.isReplaceEachRun()) {
        jdbc.update("DELETE FROM api_feed_rows WHERE source_id = ?", source.getId());
      }

      List<Object[]> batch = new ArrayList<>(records.size());
      for (int i = 0; i < records.size(); i++) {
        JsonNode record = records.get(i);
        batch.add(
            new Object[] {
              UUID.randomUUID().toString(),
              source.getId(),
              source.getName(),
              java.sql.Timestamp.from(now),
              i,
              key(record, source.getKeyField()),
              record.toString()
            });
      }
      if (!batch.isEmpty()) {
        jdbc.batchUpdate(
            "INSERT INTO api_feed_rows "
                + "(id, source_id, source_name, fetched_at, record_index, record_key, payload_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            batch);
      }

      String message =
          "Wrote "
              + batch.size()
              + " record(s)"
              + (source.isReplaceEachRun() ? ", replacing the previous run." : ", appended.");
      return record(source, true, batch.size(), message, now);

    } catch (Exception e) {
      return record(source, false, 0, e.getClass().getSimpleName() + ": " + e.getMessage(), now);
    }
  }

  private RunResult record(ApiSource source, boolean ok, int rows, String message, Instant at) {
    source.setLastRunAt(at);
    source.setLastStatus(ok ? "OK" : "FAILED");
    source.setLastMessage(truncate(message, 900));
    source.setLastRowCount(rows);
    sources.save(source);
    if (!ok) {
      log.warn("API source \"{}\" failed: {}", source.getName(), message);
    }
    return new RunResult(ok, rows, message, at);
  }

  // ---------------------------------------------------------------- the poller

  /**
   * The background thread. Wakes on a fixed tick and runs whatever is due.
   *
   * <p>A tick plus a per-source interval, rather than a scheduled job per source: sources are created and
   * edited at runtime, and one thread checking a handful of rows every few seconds is cheaper and far
   * easier to reason about than a scheduler being reprogrammed from a web request. The cost is that an
   * interval is honoured to within one tick, which for a feed measured in minutes is not a cost.
   *
   * <p>{@code fixedDelay} rather than {@code fixedRate}, so a slow endpoint delays the next tick instead of
   * queueing ticks behind itself.
   */
  @Scheduled(fixedDelayString = "${dashboard.data-source.poll-tick-seconds:15}", timeUnit = java.util.concurrent.TimeUnit.SECONDS)
  public void pollDueSources() {
    if (!props.isPollingEnabled()) {
      return;
    }
    Instant now = Instant.now();
    for (ApiSource source : sources.findAll()) {
      if (source.isDue(now)) {
        log.debug("API source \"{}\" is due", source.getName());
        run(source);
      }
    }
  }

  // ---------------------------------------------------------------- helpers

  /** Records currently held for a source, newest first, for the screen. */
  public List<Map<String, Object>> recentRows(String sourceId, int limit) {
    return jdbc.queryForList(
        "SELECT record_index, record_key, fetched_at, payload_json FROM api_feed_rows "
            + "WHERE source_id = ? ORDER BY fetched_at DESC, record_index ASC LIMIT ?",
        sourceId,
        Math.max(1, Math.min(limit, 100)));
  }

  public long rowCount(String sourceId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM api_feed_rows WHERE source_id = ?", Long.class, sourceId);
    return count == null ? 0 : count;
  }

  /**
   * Checks a URL before it is fetched.
   *
   * @throws IllegalArgumentException when it is not an absolute http(s) URL, or points somewhere this
   *     instance is configured not to reach
   */
  public URI validated(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("A URL is required.");
    }
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("That is not a valid URL.");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new IllegalArgumentException("Only http and https URLs can be configured.");
    }
    if (uri.getHost() == null) {
      throw new IllegalArgumentException("That URL has no host.");
    }
    if (!props.isAllowPrivateHosts() && isPrivate(uri.getHost())) {
      throw new IllegalArgumentException(
          "This instance is configured not to call private or loopback addresses.");
    }
    return uri;
  }

  private static boolean isPrivate(String host) {
    try {
      var address = java.net.InetAddress.getByName(host);
      return address.isLoopbackAddress()
          || address.isSiteLocalAddress()
          || address.isLinkLocalAddress()
          || address.isAnyLocalAddress();
    } catch (Exception e) {
      // An unresolvable host is not reachable either; let the fetch report that itself.
      return false;
    }
  }

  private HttpResponse<String> fetch(URI uri, String headersJson) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .GET()
            .timeout(Duration.ofSeconds(props.getRequestTimeoutSeconds()))
            .header("accept", "application/json");

    for (var entry : headerMap(headersJson).entrySet()) {
      // Restricted headers (host, connection, content-length…) are refused by the client itself.
      try {
        request.setHeader(entry.getKey(), entry.getValue());
      } catch (IllegalArgumentException ignored) {
        log.debug("Ignoring header {} — the HTTP client does not allow setting it", entry.getKey());
      }
    }

    HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (response.body() != null && response.body().length() > props.getMaxResponseBytes()) {
      throw new IllegalArgumentException(
          "The response is larger than the configured limit of " + props.getMaxResponseBytes() + " bytes.");
    }
    return response;
  }

  /** Parses the configured headers object; a malformed one is a configuration error worth reporting. */
  public Map<String, String> headerMap(String headersJson) {
    if (headersJson == null || headersJson.isBlank()) {
      return Map.of();
    }
    try {
      JsonNode node = mapper.readTree(headersJson);
      if (!node.isObject()) {
        throw new IllegalArgumentException("Headers must be a JSON object, for example {\"X-Api-Key\": \"…\"}.");
      }
      Map<String, String> headers = new LinkedHashMap<>();
      node.fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
      return headers;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Headers are not valid JSON: " + e.getMessage());
    }
  }

  /** Walks a dotted path. A blank path is the root. */
  public static JsonNode atPath(JsonNode root, String path) {
    if (root == null) {
      return null;
    }
    if (path == null || path.isBlank()) {
      return root;
    }
    JsonNode at = root;
    for (String segment : path.trim().split("\\.")) {
      if (segment.isBlank()) {
        continue;
      }
      if (at == null) {
        return null;
      }
      at = at.isArray() && segment.matches("\\d+") ? at.get(Integer.parseInt(segment)) : at.get(segment);
    }
    return at;
  }

  /** An array becomes its elements; anything else is a single record. */
  public static List<JsonNode> records(JsonNode at) {
    if (at.isArray()) {
      List<JsonNode> out = new ArrayList<>(at.size());
      at.forEach(out::add);
      return out;
    }
    return List.of(at);
  }

  private static String key(JsonNode record, String keyField) {
    if (keyField == null || keyField.isBlank() || !record.isObject()) {
      return null;
    }
    JsonNode value = record.get(keyField.trim());
    if (value == null || value.isNull()) {
      return null;
    }
    String text = value.isValueNode() ? value.asText() : value.toString();
    return truncate(text, 240);
  }

  private static List<String> fieldList(JsonNode node) {
    List<String> names = new ArrayList<>();
    if (node.isObject()) {
      node.fieldNames().forEachRemaining(names::add);
    }
    return names;
  }

  private static String fieldNames(JsonNode node) {
    List<String> names = fieldList(node);
    return names.isEmpty() ? "(none — it is not an object)" : String.join(", ", names);
  }

  private static String contentType(HttpResponse<?> response) {
    return response.headers().firstValue("content-type").orElse(null);
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }
}
