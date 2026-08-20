package com.leadsquared.peopleinsights.datasource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The tables an import may write to, and the columns each one has, read from the live schema.
 *
 * <p>This exists to make a generic importer safe. A table name and a column name cannot be parameters in
 * SQL — they are identifiers, so they end up concatenated into the statement — which means the only way
 * to accept either from a request is to check it against something authoritative first. Here that is
 * {@code information_schema}: a name that is not a real column of a real table in this schema never
 * reaches a statement, and the names that do reach one are the database's own spelling of them rather
 * than the caller's.
 *
 * <p>Configuration and audit tables are excluded outright. Loading rows into {@code app_users} or
 * {@code custom_roles} from a spreadsheet would be a way to grant access without going through the access
 * page, and loading rows into {@code audit_events} would be a way to write history that never happened.
 * Neither belongs behind a file picker.
 */
@Service
public class TableCatalog {

  /**
   * Tables no import may touch, whatever the file says.
   *
   * <p>The first three are the access model, the fourth is the record of who read what, and the last two
   * are written by the application about its own runs. A spreadsheet is not an appropriate way to change
   * any of them.
   */
  private static final Set<String> EXCLUDED =
      Set.of(
          "app_users",
          "custom_roles",
          "bu_assignments",
          "audit_events",
          "ingest_runs",
          "narratives",
          "narrative_anomalies",
          "narrative_cited_figures");

  private final JdbcTemplate jdbc;
  private final String schema;

  public TableCatalog(JdbcTemplate jdbc, DataSource dataSource) {
    this.jdbc = jdbc;
    this.schema = currentSchema(jdbc);
  }

  private static String currentSchema(JdbcTemplate jdbc) {
    return jdbc.queryForObject("SELECT DATABASE()", String.class);
  }

  /**
   * @param name the database's spelling of the table name
   * @param columns every column, in ordinal order
   * @param rowCount rows currently held, so the screen can say what a replace would discard
   */
  public record TableInfo(String name, List<ColumnInfo> columns, long rowCount) {}

  /**
   * @param nullable whether a row can omit this column
   * @param autoOrDefaulted the database fills this in when a row omits it
   */
  public record ColumnInfo(
      String name, String type, boolean nullable, boolean autoOrDefaulted, boolean primaryKey) {}

  /** Every importable table with its columns, cheapest-to-read first: no row counts. */
  public List<String> importableTableNames() {
    return jdbc
        .queryForList(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name",
            String.class,
            schema)
        .stream()
        .filter(name -> !EXCLUDED.contains(name.toLowerCase(Locale.ROOT)))
        .toList();
  }

  /** Every importable table, with columns and current row counts. */
  public List<TableInfo> importableTables() {
    Map<String, List<ColumnInfo>> byTable = columnsBySchemaTable();
    return importableTableNames().stream()
        .map(name -> new TableInfo(name, byTable.getOrDefault(name, List.of()), rowCount(name)))
        .toList();
  }

  /**
   * The table as the database spells it, or empty when it is not an importable table here.
   *
   * <p>Every caller that is about to build a statement goes through this. Returning the database's own
   * name rather than the caller's is what makes the result safe to concatenate.
   */
  public Optional<TableInfo> resolve(String requestedTable) {
    if (requestedTable == null || requestedTable.isBlank()) {
      return Optional.empty();
    }
    String wanted = requestedTable.trim();
    return importableTables().stream()
        .filter(t -> t.name().equalsIgnoreCase(wanted))
        .findFirst();
  }

  /** Rows currently in a table already resolved through {@link #resolve}. */
  public long rowCount(String resolvedTable) {
    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM `" + resolvedTable + "`", Long.class);
    return count == null ? 0 : count;
  }

  private Map<String, List<ColumnInfo>> columnsBySchemaTable() {
    Map<String, List<ColumnInfo>> byTable = new LinkedHashMap<>();
    jdbc.query(
        "SELECT table_name, column_name, column_type, is_nullable, column_default, extra, column_key "
            + "FROM information_schema.columns WHERE table_schema = ? ORDER BY table_name, ordinal_position",
        rs -> {
          String table = rs.getString("table_name");
          boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
          String extra = rs.getString("extra");
          boolean defaulted =
              rs.getString("column_default") != null
                  || (extra != null && extra.toLowerCase(Locale.ROOT).contains("auto_increment"));
          byTable
              .computeIfAbsent(table, t -> new java.util.ArrayList<>())
              .add(
                  new ColumnInfo(
                      rs.getString("column_name"),
                      rs.getString("column_type"),
                      nullable,
                      defaulted,
                      "PRI".equalsIgnoreCase(rs.getString("column_key"))));
        },
        schema);
    return byTable;
  }

  /**
   * Matches a spreadsheet heading to a column of the table.
   *
   * <p>Tolerant on purpose, because a heading is written by a person: "Employee ID", "employee_id" and
   * "employeeid" all find {@code employee_id}. Nothing beyond that — no synonyms, no guessing at intent —
   * so a heading that does not match is reported as skipped rather than quietly loaded into the wrong
   * column, which is the failure that matters here.
   */
  public static Optional<ColumnInfo> matchColumn(String heading, List<ColumnInfo> columns) {
    if (heading == null || heading.isBlank()) {
      return Optional.empty();
    }
    String wanted = squash(heading);
    return columns.stream().filter(c -> squash(c.name()).equals(wanted)).findFirst();
  }

  private static String squash(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }
}
