package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.IngestRun;
import com.leadsquared.peopleinsights.repo.IngestRunRepo;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Holds assembled datasets in memory between ingests.
 *
 * <p>Assembling one {@link Dataset} reads roughly 51,000 documents from the cluster — every employee,
 * their compensation, leave balance, six months of attendance, eNPS response and exit record. That is
 * 15 to 20 seconds against Atlas even with the six reads issued in parallel, and the landing page
 * alone assembles three of them (metric cards, calendar, narrative). Uncached, the dashboard is
 * correct and unusable at the same time.
 *
 * <p>Caching is safe here because the underlying data is a snapshot rather than a live feed: the
 * workbooks change only at ingest, and {@link Dataset} is a record whose collections nothing mutates
 * after assembly, so one instance can be shared by every concurrent reader.
 *
 * <p>Three properties make it correct rather than merely fast:
 *
 * <ul>
 *   <li><b>The key is the whole identity of the data.</b> Business units, label, as-of anchor and the
 *       full filter spec. Since the authorised BU list can only come from {@code ScopeGuard}, a
 *       cached entry cannot be served to a caller whose scope differs from the one that loaded it —
 *       the key would not match. Caching therefore cannot widen what anybody sees.
 *   <li><b>Every entry is stamped with the ingest generation.</b> A fresh ingest changes the
 *       generation, so all existing entries stop matching at once. {@code IngestService} also clears
 *       the cache outright; the generation stamp is the backstop for a re-ingest this process did not
 *       perform.
 *   <li><b>One load per key, not one per waiter.</b> A second request for a key already being
 *       assembled waits for the first rather than starting a duplicate 20-second read.
 * </ul>
 */
@Service
public class DatasetCache {

  private static final Logger log = LoggerFactory.getLogger(DatasetCache.class);

  /**
   * Everything that determines a dataset's contents, and nothing that does not.
   *
   * <p>List order is part of equality. {@code ScopeGuard} resolves business units deterministically
   * and the UI submits filters in a stable order, so in practice this costs nothing; if an order ever
   * did vary, the effect is a redundant entry rather than a wrong answer, which is the right way for
   * a cache key to fail.
   */
  private record Key(
      String generation,
      List<String> businessUnits,
      String label,
      LocalDate asOf,
      FilterSpec filters) {}

  private static final class Entry {
    private final CompletableFuture<Dataset> value = new CompletableFuture<>();
    private final AtomicLong lastReadNanos = new AtomicLong(System.nanoTime());
  }

  private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
  private final IngestRunRepo ingestRuns;
  private final int maxEntries;
  private final long revalidateNanos;

  private volatile String generation;
  private volatile long generationReadNanos;

  public DatasetCache(AppProperties props, IngestRunRepo ingestRuns) {
    this.ingestRuns = ingestRuns;
    this.maxEntries = props.getDatasetCacheEntries();
    this.revalidateNanos =
        TimeUnit.SECONDS.toNanos(Math.max(0, props.getDatasetCacheRevalidateSeconds()));
  }

  /**
   * The dataset for this scope and filter combination, assembled by {@code load} on a miss.
   *
   * @param load called at most once per key per ingest generation
   */
  public Dataset get(
      List<String> businessUnits,
      String label,
      LocalDate asOf,
      FilterSpec filters,
      Supplier<Dataset> load) {

    if (maxEntries <= 0) {
      return load.get();
    }

    Key key = new Key(generation(), List.copyOf(businessUnits), label, asOf, filters);
    Entry mine = new Entry();
    Entry existing = entries.putIfAbsent(key, mine);

    if (existing != null) {
      existing.lastReadNanos.set(System.nanoTime());
      return join(existing.value);
    }

    try {
      Dataset loaded = load.get();
      mine.value.complete(loaded);
      evictDownToCap();
      log.debug(
          "Dataset assembled and cached: {} ({} employees), {} entries held",
          label,
          loaded.size(),
          entries.size());
      return loaded;
    } catch (RuntimeException | Error failure) {
      // A failed load must not be remembered, and anyone already waiting on it has to be released
      // with the failure rather than left blocked on a future that will never complete.
      entries.remove(key, mine);
      mine.value.completeExceptionally(failure);
      throw failure;
    }
  }

  /** Drops every entry. Called when this process re-ingests the workbooks. */
  public void invalidateAll() {
    int held = entries.size();
    entries.clear();
    generation = null;
    if (held > 0) {
      log.info("Dataset cache cleared ({} entries) — datasets will be reassembled on next request", held);
    }
  }

  /** Entries currently held, for tests and diagnostics. */
  public int size() {
    return entries.size();
  }

  /**
   * The id of the latest successful ingest, re-read at most once per revalidation window.
   *
   * <p>Two threads may occasionally re-read it together. That is harmless — they arrive at the same
   * answer — and avoiding it would mean locking every request behind a mutex to save a lookup that
   * happens twice a minute.
   */
  private String generation() {
    long now = System.nanoTime();
    String current = generation;
    if (current != null && now - generationReadNanos < revalidateNanos) {
      return current;
    }
    String latest =
        ingestRuns
            .findFirstByStatusOrderByFinishedAtDesc("SUCCESS")
            .map(IngestRun::id)
            .orElse("no-ingest");
    if (current != null && !current.equals(latest)) {
      log.info("New ingest detected ({} -> {}) — dropping cached datasets", current, latest);
      entries.clear();
    }
    generation = latest;
    generationReadNanos = now;
    return latest;
  }

  /**
   * Evicts least-recently-read entries until the cap is met.
   *
   * <p>Bounded by attempt count as well as by size: a concurrent removal can make an individual
   * {@code remove} miss, and a cache is never worth spinning in.
   */
  private void evictDownToCap() {
    int attempts = 0;
    while (entries.size() > maxEntries && attempts++ < maxEntries + 8) {
      entries.entrySet().stream()
          .min(Comparator.comparingLong(e -> e.getValue().lastReadNanos.get()))
          .ifPresent(oldest -> remove(oldest));
    }
  }

  private void remove(Map.Entry<Key, Entry> entry) {
    if (entries.remove(entry.getKey(), entry.getValue())) {
      log.debug("Evicted cached dataset: {}", entry.getKey().label());
    }
  }

  /** Unwraps the completion wrapper so a waiter sees the same exception the loader threw. */
  private static Dataset join(CompletableFuture<Dataset> future) {
    try {
      return future.join();
    } catch (CompletionException wrapped) {
      Throwable cause = wrapped.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw wrapped;
    }
  }
}
