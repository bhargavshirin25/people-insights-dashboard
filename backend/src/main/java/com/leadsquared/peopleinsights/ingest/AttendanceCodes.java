package com.leadsquared.peopleinsights.ingest;

/**
 * The attendance notation set, taken from the workbook's own legend sheet.
 *
 * <p>Codes carrying a ",R" suffix are the same underlying state plus a pending regularisation
 * request. They are counted in their base category and separately tallied as regularisations, since
 * a spike in regularisation requests is itself an attendance-health signal.
 */
final class AttendanceCodes {

  static final String PRESENT = "Attendance";
  static final String ABSENT = "Absent";
  static final String LOP = "LOP";
  static final String LEAVE = "Leave";
  static final String HALF_DAY = "Half Day";
  static final String WEEK_OFF = "Week Off";
  static final String UNKNOWN = "Unknown";

  private AttendanceCodes() {}

  /** Maps a raw cell code to its legend category. */
  static String category(String code) {
    if (code == null) {
      return UNKNOWN;
    }
    return switch (code.trim().toUpperCase()) {
      case "P" -> PRESENT;
      case "A", "A,R", "SA", "SA,R" -> ABSENT;
      case "U", "R,U" -> LOP;
      case "L" -> LEAVE;
      case "0.5" -> HALF_DAY;
      case "WO" -> WEEK_OFF;
      default -> UNKNOWN;
    };
  }

  /** True for the two single-punch codes, which indicate a missing entry or exit swipe. */
  static boolean isSinglePunch(String code) {
    if (code == null) {
      return false;
    }
    String c = code.trim().toUpperCase();
    return c.equals("SA") || c.equals("SA,R");
  }

  /** True when the employee raised a regularisation request against the day. */
  static boolean hasRegularisation(String code) {
    return code != null && code.trim().toUpperCase().contains("R");
  }
}
