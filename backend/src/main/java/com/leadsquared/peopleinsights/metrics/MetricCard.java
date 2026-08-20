package com.leadsquared.peopleinsights.metrics;

/**
 * One metric card: current value, month-on-month movement in both absolute and percentage terms, and
 * a colour-coded direction.
 *
 * <p>{@code available} exists because two cards the brief asks for cannot be computed from the Phase 1
 * extract — open approved positions has no source dataset, and eNPS has a single survey cycle so there
 * is no prior period to compare against. Those render an explicit "not available" state carrying the
 * reason, rather than a zero or an invented number, because the narrative layer is required to cite
 * only figures that appear on a card.
 */
public record MetricCard(
    String key,
    String label,
    Double value,
    String displayValue,
    String unit,
    Double momAbsolute,
    Double momPercent,
    /** up | down | flat */
    String direction,
    /** green | amber | red */
    String sentiment,
    /** Higher values are better for this metric. Drives the colour, not the arrow. */
    boolean higherIsBetter,
    boolean available,
    String unavailableReason,
    String basis) {

  private static final double FLAT_THRESHOLD_PCT = 1.0;
  private static final double MATERIAL_THRESHOLD_PCT = 10.0;

  /** A computed card, with direction and colour derived from the movement. */
  public static MetricCard of(
      String key,
      String label,
      double value,
      String displayValue,
      String unit,
      Double previous,
      boolean higherIsBetter,
      String basis) {

    Double momAbs = previous == null ? null : round(value - previous);
    Double momPct =
        previous == null || previous == 0.0 ? null : round(((value - previous) / Math.abs(previous)) * 100.0);

    String direction = "flat";
    String sentiment = "green";
    if (momAbs != null) {
      double pct = momPct == null ? 0.0 : momPct;
      if (Math.abs(pct) < FLAT_THRESHOLD_PCT) {
        direction = "flat";
        sentiment = "green";
      } else {
        direction = momAbs > 0 ? "up" : "down";
        boolean favourable = higherIsBetter == (momAbs > 0);
        sentiment = favourable ? "green" : Math.abs(pct) > MATERIAL_THRESHOLD_PCT ? "red" : "amber";
      }
    }

    return new MetricCard(
        key,
        label,
        round(value),
        displayValue,
        unit,
        momAbs,
        momPct,
        direction,
        sentiment,
        higherIsBetter,
        true,
        null,
        basis);
  }

  /** A card whose value exists but has no comparable prior period. */
  public static MetricCard withoutComparison(
      String key,
      String label,
      double value,
      String displayValue,
      String unit,
      String reason,
      boolean higherIsBetter,
      String basis) {
    return new MetricCard(
        key,
        label,
        round(value),
        displayValue,
        unit,
        null,
        null,
        "flat",
        "green",
        higherIsBetter,
        true,
        reason,
        basis);
  }

  /** A card with no source data at all. */
  public static MetricCard unavailable(String key, String label, String reason) {
    return new MetricCard(
        key, label, null, "—", null, null, null, "flat", "amber", true, false, reason, null);
  }

  private static Double round(double v) {
    return Math.round(v * 100.0) / 100.0;
  }
}
