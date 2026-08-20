package com.leadsquared.peopleinsights.datasource;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * A spreadsheet or CSV read as headings plus rows of strings.
 *
 * <p>Everything is a string at this stage, deliberately. The database is the thing that knows what a
 * column holds, and MySQL parses "2026-07-31" into a DATE and "12.5" into a DECIMAL on its own — so
 * converting here would mean reimplementing that, badly, and having two opinions about what a cell means.
 * The one exception is a date-formatted spreadsheet cell, which POI hands over as a floating-point serial
 * number that no database would recognise, so it is rendered back into an ISO date here.
 */
public final class TabularFile {

  /**
   * @param headings the first row, in file order
   * @param rows every subsequent row, padded to the heading count
   * @param truncated more rows existed than the cap allowed
   */
  public record Content(List<String> headings, List<List<String>> rows, boolean truncated) {}

  private TabularFile() {}

  /** Reads a file by its name's extension. */
  public static Content read(String filename, InputStream in, int maxRows) throws IOException {
    String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
      return readWorkbook(in, maxRows);
    }
    if (lower.endsWith(".xls")) {
      throw new IllegalArgumentException(
          "The old .xls format is not supported — save the sheet as .xlsx or .csv and try again.");
    }
    return readCsv(in, maxRows);
  }

  /**
   * CSV, through the format that handles the cases a hand-rolled split does not: quoted fields holding
   * commas or newlines, doubled quotes, and a trailing blank line.
   */
  static Content readCsv(InputStream in, int maxRows) throws IOException {
    CSVFormat format =
        CSVFormat.DEFAULT
            .builder()
            .setIgnoreSurroundingSpaces(true)
            .setIgnoreEmptyLines(true)
            .build();

    try (Reader reader = new InputStreamReader(stripBom(in), StandardCharsets.UTF_8);
        CSVParser parser = CSVParser.parse(reader, format)) {

      List<String> headings = List.of();
      List<List<String>> rows = new ArrayList<>();
      boolean truncated = false;

      for (CSVRecord record : parser) {
        List<String> values = new ArrayList<>(record.size());
        record.forEach(values::add);
        if (headings.isEmpty()) {
          headings = List.copyOf(values);
          continue;
        }
        if (rows.size() >= maxRows) {
          truncated = true;
          break;
        }
        if (values.stream().allMatch(v -> v == null || v.isBlank())) {
          continue;
        }
        rows.add(pad(values, headings.size()));
      }
      return new Content(headings, rows, truncated);
    }
  }

  /** The first sheet of an .xlsx workbook. */
  static Content readWorkbook(InputStream in, int maxRows) throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook(new BufferedInputStream(in))) {
      Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
      if (sheet == null) {
        return new Content(List.of(), List.of(), false);
      }

      Row headerRow = sheet.getRow(sheet.getFirstRowNum());
      if (headerRow == null) {
        return new Content(List.of(), List.of(), false);
      }
      List<String> headings = new ArrayList<>();
      for (int c = 0; c < headerRow.getLastCellNum(); c++) {
        headings.add(cell(headerRow.getCell(c)));
      }

      List<List<String>> rows = new ArrayList<>();
      boolean truncated = false;
      for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
        if (rows.size() >= maxRows) {
          truncated = true;
          break;
        }
        Row row = sheet.getRow(r);
        if (row == null) {
          continue;
        }
        List<String> values = new ArrayList<>(headings.size());
        boolean anything = false;
        for (int c = 0; c < headings.size(); c++) {
          String value = cell(row.getCell(c));
          anything |= !value.isBlank();
          values.add(value);
        }
        if (anything) {
          rows.add(values);
        }
      }
      return new Content(List.copyOf(headings), rows, truncated);
    }
  }

  /** One cell as text, with a date cell rendered as an ISO date rather than a serial number. */
  private static String cell(Cell cell) {
    if (cell == null) {
      return "";
    }
    CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
    return switch (type) {
      case STRING -> cell.getStringCellValue().trim();
      case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
      case NUMERIC -> {
        if (DateUtil.isCellDateFormatted(cell)) {
          LocalDateTime when = cell.getLocalDateTimeCellValue();
          yield when == null
              ? ""
              : (when.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                  ? when.toLocalDate().toString()
                  : when.toString());
        }
        double number = cell.getNumericCellValue();
        // Whole numbers arrive as 4.0; an employee id is not "4.0".
        yield number == Math.rint(number) && !Double.isInfinite(number)
            ? String.valueOf((long) number)
            : String.valueOf(number);
      }
      default -> "";
    };
  }

  private static List<String> pad(List<String> values, int width) {
    if (values.size() == width) {
      return List.copyOf(values);
    }
    List<String> padded = new ArrayList<>(width);
    for (int i = 0; i < width; i++) {
      padded.add(i < values.size() ? values.get(i) : "");
    }
    return padded;
  }

  /** A UTF-8 byte-order mark would otherwise become part of the first heading. */
  private static InputStream stripBom(InputStream in) throws IOException {
    BufferedInputStream buffered = new BufferedInputStream(in, 4);
    buffered.mark(3);
    byte[] first = new byte[3];
    int read = buffered.read(first, 0, 3);
    if (read == 3 && (first[0] & 0xFF) == 0xEF && (first[1] & 0xFF) == 0xBB && (first[2] & 0xFF) == 0xBF) {
      return buffered;
    }
    buffered.reset();
    return buffered;
  }
}
