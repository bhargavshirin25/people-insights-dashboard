package com.leadsquared.peopleinsights.datasource;

import com.leadsquared.peopleinsights.datasource.TableCatalog.ColumnInfo;
import com.leadsquared.peopleinsights.datasource.TableCatalog.TableInfo;
import com.leadsquared.peopleinsights.metrics.DatasetCache;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a CSV or spreadsheet into one existing table.
 *
 * <p>Column-mapped rather than positional: a heading finds its column by name, unmatched headings are
 * reported as skipped, and columns the file does not mention are left to the database's own default. That
 * is what makes it usable for a correction file holding three columns as well as for a full extract.
 *
 * <p>It writes through {@code JdbcTemplate} rather than through a repository because the point is that it
 * works for any table, including one added after this was written. The table and every column are resolved
 * against {@link TableCatalog} first, so the identifiers it concatenates are the database's own.
 *
 * <p>Deliberately not a substitute for the workbook ingest. That path knows the seven HR Ops datasets —
 * their child tables, their attendance reshaping, their derived columns — and it is what a full refresh
 * should use. This is for topping up a table, correcting rows, and loading a table the ingest knows
 * nothing about.
 */
@Service
public class ImportService {

  private static final Logger log = LoggerFactory.getLogger(ImportService.class);

  /** Rows accepted from one file. Beyond this the file is truncated and the report says so. */
  private static final int MAX_ROWS = 200_000;

  /** Rows per INSERT batch. */
  private static final int BATCH = 500;

  private final JdbcTemplate jdbc;
  private final TableCatalog catalog;
  private final DatasetCache datasetCache;

  public ImportService(JdbcTemplate jdbc, TableCatalog catalog, DatasetCache datasetCache) {
    this.jdbc = jdbc;
    this.catalog = catalog;
    this.datasetCache = datasetCache;
  }

  public enum Mode {
    /** Add the file's rows to what is there. */
    APPEND,
    /** Empty the table first. */
    REPLACE
  }

  /**
   * @param mappedColumns headings that found a column, in file order
   * @param skippedHeadings headings that matched nothing in the table
   * @param unmappedColumns columns the file never mentioned, left to their defaults
   * @param rowsRead rows found in the file
   * @param rowsWritten rows the database accepted
   * @param rowsRejected rows the database refused, with the first few reasons in {@code errors}
   * @param deletedFirst rows removed by a replace
   */
  public record Report(
      String table,
      Mode mode,
      List<String> mappedColumns,
      List<String> skippedHeadings,
      List<String> unmappedColumns,
      int rowsRead,
      int rowsWritten,
      int rowsRejected,
      long deletedFirst,
      boolean fileTruncated,
      List<String> errors) {}

  /**
   * Reads the file and writes it.
   *
   * <p>One transaction: a replace that empties the table and then fails on row nine hundred must not
   * leave the table empty. Either the import lands or the table is as it was.
   */
  @Transactional
  public Report importFile(String filename, InputStream in, String requestedTable, Mode mode)
      throws IOException {

    TableInfo table =
        catalog
            .resolve(requestedTable)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "\"" + requestedTable + "\" is not a table this import can write to."));

    TabularFile.Content content = TabularFile.read(filename, in, MAX_ROWS);
    if (content.headings().isEmpty()) {
      throw new IllegalArgumentException("That file has no heading row, so nothing can be mapped.");
    }

    // Heading position -> column, so a row's values can be placed without another lookup per row.
    List<Integer> positions = new ArrayList<>();
    List<ColumnInfo> targets = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (int i = 0; i < content.headings().size(); i++) {
      String heading = content.headings().get(i);
      if (heading == null || heading.isBlank()) {
        continue;
      }
      var match = TableCatalog.matchColumn(heading, table.columns());
      if (match.isPresent() && !targets.contains(match.get())) {
        positions.add(i);
        targets.add(match.get());
      } else {
        skipped.add(heading);
      }
    }

    if (targets.isEmpty()) {
      throw new IllegalArgumentException(
          "None of the headings in that file match a column of "
              + table.name()
              + ". Its columns are: "
              + table.columns().stream().map(ColumnInfo::name).limit(12).toList());
    }

    // A row the database cannot identify is a row that will fail on insert; say so before writing.
    List<String> missingRequired =
        table.columns().stream()
            .filter(c -> !c.nullable() && !c.autoOrDefaulted())
            .filter(c -> targets.stream().noneMatch(t -> t.name().equals(c.name())))
            .map(ColumnInfo::name)
            .toList();
    if (!missingRequired.isEmpty()) {
      throw new IllegalArgumentException(
          "The file does not provide "
              + String.join(", ", missingRequired)
              + ", which "
              + table.name()
              + " requires. Add "
              + (missingRequired.size() == 1 ? "that column" : "those columns")
              + " as headings and try again.");
    }

    long deleted = 0;
    if (mode == Mode.REPLACE) {
      deleted = jdbc.update("DELETE FROM `" + table.name() + "`");
    }

    String sql =
        "INSERT INTO `"
            + table.name()
            + "` ("
            + targets.stream().map(c -> "`" + c.name() + "`").collect(java.util.stream.Collectors.joining(", "))
            + ") VALUES ("
            + targets.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(", "))
            + ")";

    List<Object[]> batch = new ArrayList<>(BATCH);
    int written = 0;
    int rejected = 0;
    List<String> errors = new ArrayList<>();

    for (int r = 0; r < content.rows().size(); r++) {
      List<String> row = content.rows().get(r);
      Object[] values = new Object[targets.size()];
      for (int c = 0; c < targets.size(); c++) {
        String raw = positions.get(c) < row.size() ? row.get(positions.get(c)) : null;
        // A blank cell is absent, not the empty string: "" in a DATE column is an error, NULL is a gap.
        values[c] = raw == null || raw.isBlank() ? null : raw;
      }
      batch.add(values);

      if (batch.size() >= BATCH || r == content.rows().size() - 1) {
        var outcome = writeBatch(sql, batch, r);
        written += outcome.written();
        rejected += outcome.rejected();
        outcome.errors().stream().limit(Math.max(0, 5 - errors.size())).forEach(errors::add);
        batch.clear();
      }
    }

    List<String> mapped = targets.stream().map(ColumnInfo::name).toList();
    List<String> untouched =
        table.columns().stream()
            .map(ColumnInfo::name)
            .filter(name -> !mapped.contains(name))
            .toList();

    // The dashboard reads assembled datasets from memory; a table it draws from has just changed.
    datasetCache.invalidateAll();

    log.info(
        "Imported {} row(s) into {} from {} ({} mapped column(s), {} rejected)",
        written,
        table.name(),
        filename,
        mapped.size(),
        rejected);

    return new Report(
        table.name(),
        mode,
        mapped,
        skipped,
        untouched,
        content.rows().size(),
        written,
        rejected,
        deleted,
        content.truncated(),
        errors);
  }

  private record BatchOutcome(int written, int rejected, List<String> errors) {}

  /**
   * Writes one batch, falling back to row-at-a-time when the batch fails.
   *
   * <p>A batch that fails says only that one of five hundred rows was wrong. Retrying the rows
   * individually costs an extra pass over a batch that was going to fail anyway, and buys the thing the
   * person doing the import actually needs: which row, and why.
   */
  private BatchOutcome writeBatch(String sql, List<Object[]> batch, int lastRowIndex) {
    try {
      int[] counts = jdbc.batchUpdate(sql, batch);
      int written = 0;
      for (int count : counts) {
        written += count > 0 ? count : (count == java.sql.Statement.SUCCESS_NO_INFO ? 1 : 0);
      }
      return new BatchOutcome(written, 0, List.of());
    } catch (RuntimeException batchFailure) {
      int written = 0;
      int rejected = 0;
      List<String> errors = new ArrayList<>();
      int firstRowNumber = lastRowIndex + 2 - batch.size() + 1; // +2 for the heading row and 1-based rows
      for (int i = 0; i < batch.size(); i++) {
        try {
          written += jdbc.update(sql, batch.get(i));
        } catch (RuntimeException rowFailure) {
          rejected++;
          if (errors.size() < 5) {
            errors.add("Row " + (firstRowNumber + i) + ": " + rootMessage(rowFailure));
          }
        }
      }
      return new BatchOutcome(written, rejected, errors);
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    return message.length() <= 240 ? message : message.substring(0, 240) + "…";
  }
}
