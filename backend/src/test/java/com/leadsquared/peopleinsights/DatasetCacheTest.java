package com.leadsquared.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.IngestRun;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.DatasetCache;
import com.leadsquared.peopleinsights.metrics.FilterSpec;
import com.leadsquared.peopleinsights.repo.IngestRunRepo;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dataset cache, which is what makes a view answer in milliseconds instead of twenty seconds.
 *
 * <p>The properties worth protecting are correctness ones rather than speed ones: a scope must never
 * be served another scope's data, a new ingest must not keep serving the previous snapshot, and a
 * failed read must not be remembered as an answer.
 */
class DatasetCacheTest {

  private static final LocalDate AS_OF = LocalDate.of(2026, 7, 31);

  private DatasetCache cache(IngestRunRepo runs, int maxEntries) {
    AppProperties props = new AppProperties();
    props.setDatasetCacheEntries(maxEntries);
    // Zero revalidation delay, so a test that changes the ingest generation sees it immediately
    // rather than waiting out a window.
    props.setDatasetCacheRevalidateSeconds(0);
    return new DatasetCache(props, runs);
  }

  private Optional<IngestRun> run(String runId) {
    return Optional.of(
        IngestRun.of(
            runId, Instant.EPOCH, Instant.EPOCH, "/tmp", Map.of(), List.of(), "2026-07-31", "SUCCESS"));
  }

  /** A repo reporting the same successful ingest on every lookup. */
  private IngestRunRepo runsAt(String runId) {
    IngestRunRepo runs = mock(IngestRunRepo.class);
    when(runs.findFirstByStatusOrderByFinishedAtDesc("SUCCESS")).thenReturn(run(runId));
    return runs;
  }

  private Dataset empty(String label) {
    return new Dataset(
        List.of("Engineering"),
        label,
        AS_OF,
        FilterSpec.none(),
        List.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        List.of());
  }

  private Dataset get(DatasetCache cache, List<String> bus, FilterSpec filters, AtomicInteger loads) {
    return cache.get(
        bus,
        "label",
        AS_OF,
        filters,
        () -> {
          loads.incrementAndGet();
          return empty("label");
        });
  }

  @Test
  @DisplayName("a repeated request assembles the dataset once")
  void repeatedRequestIsServedFromCache() {
    DatasetCache cache = cache(runsAt("run-1"), 16);
    AtomicInteger loads = new AtomicInteger();

    Dataset first = get(cache, List.of("Engineering"), FilterSpec.none(), loads);
    Dataset second = get(cache, List.of("Engineering"), FilterSpec.none(), loads);

    assertThat(loads.get()).isEqualTo(1);
    assertThat(second).isSameAs(first);
  }

  @Test
  @DisplayName("a different scope is a different entry, never a shared one")
  void scopeIsPartOfTheKey() {
    DatasetCache cache = cache(runsAt("run-1"), 16);
    AtomicInteger loads = new AtomicInteger();

    get(cache, List.of("Engineering"), FilterSpec.none(), loads);
    get(cache, List.of("Sales"), FilterSpec.none(), loads);
    get(cache, List.of("Engineering", "Sales"), FilterSpec.none(), loads);

    assertThat(loads.get()).isEqualTo(3);
    assertThat(cache.size()).isEqualTo(3);
  }

  @Test
  @DisplayName("a different filter combination is a different entry")
  void filtersArePartOfTheKey() {
    DatasetCache cache = cache(runsAt("run-1"), 16);
    AtomicInteger loads = new AtomicInteger();
    FilterSpec bengaluru =
        new FilterSpec(List.of(), List.of("Bengaluru"), null, null, "LAST_30_DAYS", null, null);

    get(cache, List.of("Engineering"), FilterSpec.none(), loads);
    get(cache, List.of("Engineering"), bengaluru, loads);
    get(cache, List.of("Engineering"), bengaluru, loads);

    assertThat(loads.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("a new ingest stops the previous snapshot being served")
  void newIngestGenerationInvalidates() {
    IngestRunRepo runs = mock(IngestRunRepo.class);
    when(runs.findFirstByStatusOrderByFinishedAtDesc("SUCCESS"))
        .thenReturn(run("run-1"), run("run-2"));
    DatasetCache cache = cache(runs, 16);
    AtomicInteger loads = new AtomicInteger();

    get(cache, List.of("Engineering"), FilterSpec.none(), loads);
    get(cache, List.of("Engineering"), FilterSpec.none(), loads);

    assertThat(loads.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("invalidateAll forces the next request to reassemble")
  void invalidateAllClearsEverything() {
    DatasetCache cache = cache(runsAt("run-1"), 16);
    AtomicInteger loads = new AtomicInteger();

    get(cache, List.of("Engineering"), FilterSpec.none(), loads);
    cache.invalidateAll();
    assertThat(cache.size()).isZero();

    get(cache, List.of("Engineering"), FilterSpec.none(), loads);
    assertThat(loads.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("the cache stays within its entry cap")
  void evictsBeyondTheCap() {
    DatasetCache cache = cache(runsAt("run-1"), 3);
    AtomicInteger loads = new AtomicInteger();

    for (int i = 0; i < 8; i++) {
      FilterSpec filters =
          new FilterSpec(List.of("G" + i), List.of(), null, null, "LAST_30_DAYS", null, null);
      get(cache, List.of("Engineering"), filters, loads);
    }

    assertThat(loads.get()).isEqualTo(8);
    assertThat(cache.size()).isLessThanOrEqualTo(3);
  }

  @Test
  @DisplayName("a failed read is not cached as an answer")
  void failedLoadIsNotRemembered() {
    DatasetCache cache = cache(runsAt("run-1"), 16);

    assertThatThrownBy(
            () ->
                cache.get(
                    List.of("Engineering"),
                    "label",
                    AS_OF,
                    FilterSpec.none(),
                    () -> {
                      throw new IllegalStateException("cluster unreachable");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("cluster unreachable");

    assertThat(cache.size()).isZero();

    AtomicInteger loads = new AtomicInteger();
    assertThat(get(cache, List.of("Engineering"), FilterSpec.none(), loads)).isNotNull();
    assertThat(loads.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("concurrent requests for one key assemble it once, not once each")
  void concurrentRequestsShareOneLoad() throws Exception {
    DatasetCache cache = cache(runsAt("run-1"), 16);
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch loadStarted = new CountDownLatch(1);
    CountDownLatch releaseLoad = new CountDownLatch(1);
    int readers = 6;

    ExecutorService pool = Executors.newFixedThreadPool(readers);
    try {
      List<Future<Dataset>> futures =
          java.util.stream.IntStream.range(0, readers)
              .mapToObj(
                  i ->
                      pool.submit(
                          () ->
                              cache.get(
                                  List.of("Engineering"),
                                  "label",
                                  AS_OF,
                                  FilterSpec.none(),
                                  () -> {
                                    loads.incrementAndGet();
                                    loadStarted.countDown();
                                    try {
                                      // Hold the read open so the others have to arrive while it
                                      // is still in flight, which is the case being tested.
                                      releaseLoad.await(5, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                      Thread.currentThread().interrupt();
                                    }
                                    return empty("label");
                                  })))
              .toList();

      assertThat(loadStarted.await(5, TimeUnit.SECONDS)).isTrue();
      releaseLoad.countDown();

      Dataset first = futures.get(0).get(10, TimeUnit.SECONDS);
      for (Future<Dataset> future : futures) {
        assertThat(future.get(10, TimeUnit.SECONDS)).isSameAs(first);
      }
      assertThat(loads.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("caching off reads every time")
  void zeroEntriesDisablesCaching() {
    DatasetCache cache = cache(runsAt("run-1"), 0);
    AtomicInteger loads = new AtomicInteger();

    get(cache, List.of("Engineering"), FilterSpec.none(), loads);
    get(cache, List.of("Engineering"), FilterSpec.none(), loads);

    assertThat(loads.get()).isEqualTo(2);
    assertThat(cache.size()).isZero();
  }
}
