package com.leadsquared.peopleinsights.ingest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

/**
 * Reads the HR Ops workbooks, which are laid out for humans rather than for parsers: a title row,
 * sometimes a subtitle, a merged group-header row ("Annual Compensation", "Monthly Breakdown"), and
 * only then the real column names — several of which contain embedded newlines.
 *
 * <p>Column lookup therefore normalises whitespace on both sides and falls back to a prefix match,
 * so "Annual Variable\nTarget (₹)" is addressable as "Annual Variable Target".
 */
final class ExcelSupport {

  private ExcelSupport() {}

  /** Date formats seen across the workbooks; the source stores dates as text. */
  private static final List<DateTimeFormatter> DATE_FORMATS =
      List.of(
          DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
          DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
          DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH),
          DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
          DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
          DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH));

  /** Column-name index for one sheet, built from a known header row. */
  static final class Header {

    private final Map<String, Integer> byName = new LinkedHashMap<>();
    private final Map<String, Integer> normalisedCache = new HashMap<>();

    Header(Sheet sheet, int headerRowIndex) {
      Row row = sheet.getRow(headerRowIndex);
      if (row == null) {
        throw new IllegalStateException(
            "Sheet '" + sheet.getSheetName() + "' has no header at row " + (headerRowIndex + 1));
      }
      for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
        String name = normalise(str(row.getCell(c)));
        if (!name.isEmpty()) {
          byName.putIfAbsent(name, c);
        }
      }
      if (byName.isEmpty()) {
        throw new IllegalStateException(
            "Sheet '" + sheet.getSheetName() + "' header row " + (headerRowIndex + 1) + " is empty");
      }
    }

    /** Resolves a column, exact-then-prefix, or -1 when absent. */
    int index(String wanted) {
      String key = normalise(wanted);
      Integer cached = normalisedCache.get(key);
      if (cached != null) {
        return cached;
      }
      Integer exact = byName.get(key);
      if (exact == null) {
        exact =
            byName.entrySet().stream()
                .filter(e -> e.getKey().startsWith(key) || e.getKey().contains(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(-1);
      }
      normalisedCache.put(key, exact);
      return exact;
    }

    int require(String wanted) {
      int idx = index(wanted);
      if (idx < 0) {
        throw new IllegalStateException(
            "Required column '" + wanted + "' not found. Available: " + byName.keySet());
      }
      return idx;
    }

    java.util.Set<String> names() {
      return byName.keySet();
    }
  }

  /**
   * Collapses newlines and repeated spaces so header text matches predictably.
   *
   * <p>Non-breaking spaces are folded to ordinary spaces first: several workbook headers
   * contain them, and {@code \s} does not match U+00A0.
   */
  static String normalise(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
  }

  static String str(Cell cell) {
    if (cell == null) {
      return "";
    }
    return switch (cell.getCellType()) {
      case STRING -> cell.getStringCellValue().trim();
      case NUMERIC -> {
        if (DateUtil.isCellDateFormatted(cell)) {
          yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        double d = cell.getNumericCellValue();
        yield d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
      }
      case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
      case FORMULA -> {
        try {
          yield cell.getStringCellValue().trim();
        } catch (IllegalStateException e) {
          yield String.valueOf(cell.getNumericCellValue());
        }
      }
      default -> "";
    };
  }

  static String str(Row row, int col) {
    return col < 0 || row == null ? "" : str(row.getCell(col));
  }

  /** Empty string becomes null, so absent values stay absent rather than becoming "". */
  static String strOrNull(Row row, int col) {
    String v = str(row, col);
    return v.isEmpty() ? null : v;
  }

  static Double dbl(Row row, int col) {
    if (col < 0 || row == null) {
      return null;
    }
    Cell cell = row.getCell(col);
    if (cell == null) {
      return null;
    }
    if (cell.getCellType() == CellType.NUMERIC) {
      return cell.getNumericCellValue();
    }
    String raw = str(cell);
    if (raw.isEmpty()) {
      return null;
    }
    // Strip currency symbols, thousands separators, percent signs and stray spaces.
    String cleaned = raw.replaceAll("[^0-9.\\-]", "");
    if (cleaned.isEmpty() || "-".equals(cleaned)) {
      return null;
    }
    try {
      return Double.valueOf(cleaned);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static Integer intg(Row row, int col) {
    Double d = dbl(row, col);
    return d == null ? null : (int) Math.round(d);
  }

  static LocalDate date(Row row, int col) {
    if (col < 0 || row == null) {
      return null;
    }
    Cell cell = row.getCell(col);
    if (cell == null) {
      return null;
    }
    if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
      return cell.getLocalDateTimeCellValue().toLocalDate();
    }
    String raw = str(cell);
    if (raw.isEmpty()) {
      return null;
    }
    for (DateTimeFormatter fmt : DATE_FORMATS) {
      try {
        return LocalDate.parse(raw, fmt);
      } catch (DateTimeParseException ignored) {
        // try the next known layout
      }
    }
    return null;
  }

  /** True when every cell in the row is blank — the workbooks end with trailing empty rows. */
  static boolean isBlank(Row row) {
    if (row == null || row.getLastCellNum() <= 0) {
      return true;
    }
    for (int c = Math.max(0, row.getFirstCellNum()); c < row.getLastCellNum(); c++) {
      if (!str(row.getCell(c)).isEmpty()) {
        return false;
      }
    }
    return true;
  }
}
