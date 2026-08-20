package com.leadsquared.peopleinsights;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadsquared.peopleinsights.datasource.ApiSourceService;
import com.leadsquared.peopleinsights.datasource.TableCatalog;
import com.leadsquared.peopleinsights.datasource.TableCatalog.ColumnInfo;
import com.leadsquared.peopleinsights.datasource.TabularFile;
import com.leadsquared.peopleinsights.domain.ApiSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of the data-source features that can be wrong quietly.
 *
 * <p>A file import and a polled feed both take input from outside and put it in a table, so the failures
 * worth testing are the ones that produce a plausible-looking result: a CSV split on the wrong commas, a
 * heading loaded into the wrong column, a JSON path that finds nothing, a poller that fires every tick
 * instead of every interval.
 */
class DataSourceTest {

  private static TabularFile.Content csv(String content) throws IOException {
    return TabularFile.read(
        "file.csv", new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), 1_000);
  }

  // ---------------------------------------------------------------- reading a file

  @Test
  @DisplayName("a quoted field holding a comma stays one field")
  void quotedCommasSurvive() throws IOException {
    var content = csv("""
        title,department
        "Manager, Regional",Sales
        Analyst,Finance
        """);

    assertThat(content.headings()).containsExactly("title", "department");
    assertThat(content.rows().get(0)).containsExactly("Manager, Regional", "Sales");
    assertThat(content.rows().get(1)).containsExactly("Analyst", "Finance");
  }

  @Test
  @DisplayName("a quoted field holding a newline stays one row")
  void quotedNewlinesSurvive() throws IOException {
    var content = csv("id,note\n1,\"line one\nline two\"\n2,plain\n");

    assertThat(content.rows()).hasSize(2);
    assertThat(content.rows().get(0).get(1)).isEqualTo("line one\nline two");
  }

  @Test
  @DisplayName("a doubled quote is one quote, not the end of the field")
  void doubledQuotesAreEscapes() throws IOException {
    var content = csv("id,note\n1,\"she said \"\"no\"\"\"\n");

    assertThat(content.rows().get(0).get(1)).isEqualTo("she said \"no\"");
  }

  @Test
  @DisplayName("a short row is padded and a blank line is dropped, so a ragged file still loads")
  void raggedRowsArePadded() throws IOException {
    var content = csv("a,b,c\n1,2\n\n3,4,5\n");

    assertThat(content.rows()).hasSize(2);
    assertThat(content.rows().get(0)).containsExactly("1", "2", "");
    assertThat(content.rows().get(1)).containsExactly("3", "4", "5");
  }

  @Test
  @DisplayName("a byte-order mark does not become part of the first heading")
  void bomIsStripped() throws IOException {
    var content = csv("﻿employee_id,grade\nLS1,M3\n");

    assertThat(content.headings().get(0)).isEqualTo("employee_id");
  }

  @Test
  @DisplayName("rows beyond the cap are dropped and the file is reported as truncated")
  void capIsReported() throws IOException {
    StringBuilder file = new StringBuilder("id\n");
    for (int i = 0; i < 50; i++) {
      file.append(i).append('\n');
    }

    var content =
        TabularFile.read(
            "big.csv", new ByteArrayInputStream(file.toString().getBytes(StandardCharsets.UTF_8)), 10);

    assertThat(content.rows()).hasSize(10);
    assertThat(content.truncated()).isTrue();
  }

  @Test
  @DisplayName("the old .xls format is refused with an instruction rather than a stack trace")
  void legacyExcelIsRefused() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> TabularFile.read("old.xls", new ByteArrayInputStream(new byte[] {1, 2}), 10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("save the sheet as .xlsx");
  }

  // ---------------------------------------------------------------- mapping headings

  private static final List<ColumnInfo> COLUMNS =
      List.of(
          new ColumnInfo("employee_id", "varchar(64)", false, false, true),
          new ColumnInfo("full_name", "varchar(240)", true, false, false),
          new ColumnInfo("date_of_joining", "date", true, false, false));

  @Test
  @DisplayName("a heading finds its column past spacing, casing and punctuation")
  void headingsMatchTolerantly() {
    assertThat(TableCatalog.matchColumn("Employee ID", COLUMNS)).map(ColumnInfo::name).contains("employee_id");
    assertThat(TableCatalog.matchColumn("employee_id", COLUMNS)).map(ColumnInfo::name).contains("employee_id");
    assertThat(TableCatalog.matchColumn("EMPLOYEEID", COLUMNS)).map(ColumnInfo::name).contains("employee_id");
    assertThat(TableCatalog.matchColumn("Date of Joining", COLUMNS))
        .map(ColumnInfo::name)
        .contains("date_of_joining");
  }

  @Test
  @DisplayName("a heading that is not a column matches nothing rather than the nearest thing")
  void unknownHeadingsDoNotGuess() {
    // "Name" is not "full_name": loading it there because it looked close is the failure this prevents.
    assertThat(TableCatalog.matchColumn("Name", COLUMNS)).isEmpty();
    assertThat(TableCatalog.matchColumn("Joining", COLUMNS)).isEmpty();
    assertThat(TableCatalog.matchColumn("", COLUMNS)).isEmpty();
    assertThat(TableCatalog.matchColumn(null, COLUMNS)).isEmpty();
  }

  // ---------------------------------------------------------------- reading an API response

  private static com.fasterxml.jackson.databind.JsonNode json(String raw) throws IOException {
    return new ObjectMapper().readTree(raw);
  }

  @Test
  @DisplayName("a dotted path walks objects and array indices, and a blank path is the root")
  void pathWalking() throws IOException {
    var root = json("{\"data\":{\"items\":[{\"id\":1},{\"id\":2}]},\"count\":2}");

    assertThat(ApiSourceService.atPath(root, "data.items").size()).isEqualTo(2);
    assertThat(ApiSourceService.atPath(root, "data.items.1").get("id").asInt()).isEqualTo(2);
    assertThat(ApiSourceService.atPath(root, "count").asInt()).isEqualTo(2);
    assertThat(ApiSourceService.atPath(root, "").isObject()).isTrue();
    assertThat(ApiSourceService.atPath(root, "nope.missing")).isNull();
  }

  @Test
  @DisplayName("an array becomes one record per element; anything else is a single record")
  void recordShapes() throws IOException {
    assertThat(ApiSourceService.records(json("[{\"a\":1},{\"a\":2},{\"a\":3}]"))).hasSize(3);
    assertThat(ApiSourceService.records(json("{\"a\":1}"))).hasSize(1);
    assertThat(ApiSourceService.records(json("[]"))).isEmpty();
  }

  // ---------------------------------------------------------------- when the poller fires

  @Test
  @DisplayName("a source is due on its interval, measured from the end of the last run")
  void dueOnInterval() {
    Instant now = Instant.parse("2026-08-19T10:00:00Z");
    ApiSource source = new ApiSource();
    source.setIntervalSeconds(300);

    // Never run: due immediately.
    assertThat(source.isDue(now)).isTrue();

    source.setLastRunAt(now.minusSeconds(120));
    assertThat(source.isDue(now)).isFalse();

    source.setLastRunAt(now.minusSeconds(300));
    assertThat(source.isDue(now)).isTrue();
  }

  @Test
  @DisplayName("a disabled source is never due, however long it has been")
  void disabledIsNeverDue() {
    ApiSource source = new ApiSource();
    source.setEnabled(false);
    source.setIntervalSeconds(60);
    source.setLastRunAt(Instant.parse("2020-01-01T00:00:00Z"));

    assertThat(source.isDue(Instant.parse("2026-08-19T10:00:00Z"))).isFalse();
  }
}
