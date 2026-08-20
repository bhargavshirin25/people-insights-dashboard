package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.domain.ExitRecord;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Exit intelligence from the NLP-processed exit records.
 *
 * <p>The point of this view is that an HRBP should never need to open an individual exit interview to
 * understand why people are leaving. That also makes the privacy rule easy to hold: verbatim quotes
 * are returned with no employee id, no name, and no department or designation, because in a team of a
 * handful of people a department plus a tenure band is enough to identify someone.
 */
@Service
public class ExitAnalyticsService {

  private static final int VERBATIM_LIMIT = 60;

  public record ThemeStat(
      String theme,
      int mentions,
      double pctOfExits,
      double negativePct,
      double neutralPct,
      double positivePct,
      double avgScore,
      String sentiment) {}

  public record TypeStat(String exitType, int count, double pct) {}

  public record BandStat(String tenureBand, int count, double pct) {}

  public record SentimentPoint(String month, int exits, double avgScore) {}

  public record ThemeTrendPoint(String month, Map<String, Double> avgScoreByTheme) {}

  /** An anonymised quote. Deliberately carries no field that could identify the individual. */
  public record Verbatim(
      String quote, String theme, String sentiment, String exitType, String tenureBand, String month) {}

  public record ExitView(
      String businessUnit,
      String periodLabel,
      String periodFrom,
      String periodTo,
      int totalExits,
      int voluntaryExits,
      int regrettableExits,
      double avgTenureMonths,
      List<ThemeStat> topThemes,
      List<TypeStat> exitTypes,
      List<BandStat> tenureBands,
      List<SentimentPoint> sentimentTrend,
      List<ThemeTrendPoint> themeTrend,
      List<Verbatim> verbatims,
      List<String> availableThemes) {}

  /**
   * @param themeFilter optional theme to restrict the verbatim list to
   */
  public ExitView build(Dataset data, String themeFilter) {
    LocalDate from = data.filters().periodStart(data.asOf());
    LocalDate to = data.filters().periodEnd(data.asOf());

    List<ExitRecord> inPeriod =
        data.exits().stream()
            .filter(x -> x.dateOfExit() != null)
            .filter(x -> !x.dateOfExit().isBefore(from) && !x.dateOfExit().isAfter(to))
            .toList();

    // A short window can contain very few exits; fall back to the full history so the view is
    // still informative, and say so through the period labels.
    boolean usedFullHistory = inPeriod.size() < 10;
    List<ExitRecord> exits = usedFullHistory ? data.exits() : inPeriod;

    int total = exits.size();
    return new ExitView(
        data.label(),
        usedFullHistory
            ? data.filters().periodLabel(data.asOf()) + " (widened to full history — too few exits in period)"
            : data.filters().periodLabel(data.asOf()),
        usedFullHistory ? null : from.toString(),
        usedFullHistory ? null : to.toString(),
        total,
        (int) exits.stream().filter(ExitRecord::isVoluntary).count(),
        (int) exits.stream().filter(ExitRecord::isRegrettable).count(),
        round(exits.stream().filter(x -> x.tenureMonths() != null)
            .mapToDouble(ExitRecord::tenureMonths).average().orElse(0)),
        themeStats(exits),
        typeStats(exits),
        bandStats(exits),
        sentimentTrend(exits),
        themeTrend(exits),
        verbatims(exits, themeFilter),
        availableThemes(exits));
  }

  // ---------------------------------------------------------------- themes

  private List<ThemeStat> themeStats(List<ExitRecord> exits) {
    // Each exit carries up to three NLP themes; a theme is counted once per exit.
    Map<String, List<ExitRecord.Theme>> byTheme = new LinkedHashMap<>();
    for (ExitRecord x : exits) {
      if (x.themes() == null) {
        continue;
      }
      for (ExitRecord.Theme t : x.themes()) {
        if (t.name() != null) {
          byTheme.computeIfAbsent(t.name(), k -> new ArrayList<>()).add(t);
        }
      }
    }
    int total = Math.max(1, exits.size());
    return byTheme.entrySet().stream()
        .map(
            e -> {
              List<ExitRecord.Theme> ts = e.getValue();
              int n = ts.size();
              long neg = ts.stream().filter(t -> "Negative".equalsIgnoreCase(t.sentiment())).count();
              long neu = ts.stream().filter(t -> "Neutral".equalsIgnoreCase(t.sentiment())).count();
              long pos = ts.stream().filter(t -> "Positive".equalsIgnoreCase(t.sentiment())).count();
              double avg =
                  ts.stream().filter(t -> t.score() != null).mapToInt(ExitRecord.Theme::score).average().orElse(0);
              String dominant = neg >= neu && neg >= pos ? "Negative" : pos >= neu ? "Positive" : "Neutral";
              return new ThemeStat(
                  e.getKey(),
                  n,
                  round(n * 100.0 / total),
                  round(neg * 100.0 / n),
                  round(neu * 100.0 / n),
                  round(pos * 100.0 / n),
                  round(avg),
                  dominant);
            })
        .sorted(Comparator.comparingInt(ThemeStat::mentions).reversed())
        .toList();
  }

  private List<String> availableThemes(List<ExitRecord> exits) {
    return exits.stream()
        .filter(x -> x.themes() != null)
        .flatMap(x -> x.themes().stream())
        .map(ExitRecord.Theme::name)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
  }

  // ---------------------------------------------------------------- breakdowns

  private List<TypeStat> typeStats(List<ExitRecord> exits) {
    int total = Math.max(1, exits.size());
    return exits.stream()
        .filter(x -> x.exitType() != null)
        .collect(Collectors.groupingBy(ExitRecord::exitType, Collectors.counting()))
        .entrySet()
        .stream()
        .map(e -> new TypeStat(e.getKey(), e.getValue().intValue(), round(e.getValue() * 100.0 / total)))
        .sorted(Comparator.comparingInt(TypeStat::count).reversed())
        .toList();
  }

  private List<BandStat> bandStats(List<ExitRecord> exits) {
    int total = Math.max(1, exits.size());
    return exits.stream()
        .filter(x -> x.tenureBand() != null)
        .collect(Collectors.groupingBy(ExitRecord::tenureBand, Collectors.counting()))
        .entrySet()
        .stream()
        .map(e -> new BandStat(e.getKey(), e.getValue().intValue(), round(e.getValue() * 100.0 / total)))
        .sorted(Comparator.comparing(b -> tenureBandOrder(b.tenureBand())))
        .toList();
  }

  /** Sorts tenure bands chronologically; the source labels use an en-dash and mixed units. */
  private static int tenureBandOrder(String band) {
    if (band == null) {
      return 99;
    }
    String b = band.toLowerCase();
    if (b.startsWith("<")) {
      return 0;
    }
    if (b.contains("3–6 months") || b.contains("3-6 months")) {
      return 1;
    }
    if (b.contains("6–12 months") || b.contains("6-12 months")) {
      return 2;
    }
    if (b.startsWith("1")) {
      return 3;
    }
    if (b.startsWith("2")) {
      return 4;
    }
    if (b.startsWith("3")) {
      return 5;
    }
    if (b.startsWith("5")) {
      return 6;
    }
    if (b.startsWith("7")) {
      return 7;
    }
    return 98;
  }

  // ---------------------------------------------------------------- trends

  private List<SentimentPoint> sentimentTrend(List<ExitRecord> exits) {
    Map<String, List<ExitRecord>> byMonth =
        exits.stream()
            .filter(x -> x.dateOfExit() != null)
            .collect(
                Collectors.groupingBy(x -> YearMonth.from(x.dateOfExit()).toString(), LinkedHashMap::new, Collectors.toList()));
    return byMonth.entrySet().stream()
        .map(
            e ->
                new SentimentPoint(
                    e.getKey(),
                    e.getValue().size(),
                    round(
                        e.getValue().stream()
                            .filter(x -> x.overallScore() != null)
                            .mapToInt(ExitRecord::overallScore)
                            .average()
                            .orElse(0))))
        .sorted(Comparator.comparing(SentimentPoint::month))
        .toList();
  }

  /** Per-month average sentiment for the five most-mentioned themes. */
  private List<ThemeTrendPoint> themeTrend(List<ExitRecord> exits) {
    List<String> top =
        themeStats(exits).stream().limit(5).map(ThemeStat::theme).toList();
    if (top.isEmpty()) {
      return List.of();
    }
    Map<String, Map<String, List<Integer>>> monthThemeScores = new LinkedHashMap<>();
    for (ExitRecord x : exits) {
      if (x.dateOfExit() == null || x.themes() == null) {
        continue;
      }
      String month = YearMonth.from(x.dateOfExit()).toString();
      for (ExitRecord.Theme t : x.themes()) {
        if (t.name() == null || t.score() == null || !top.contains(t.name())) {
          continue;
        }
        monthThemeScores
            .computeIfAbsent(month, k -> new LinkedHashMap<>())
            .computeIfAbsent(t.name(), k -> new ArrayList<>())
            .add(t.score());
      }
    }
    return monthThemeScores.entrySet().stream()
        .map(
            e -> {
              Map<String, Double> avgs = new LinkedHashMap<>();
              for (String theme : top) {
                List<Integer> scores = e.getValue().get(theme);
                if (scores != null && !scores.isEmpty()) {
                  avgs.put(theme, round(scores.stream().mapToInt(Integer::intValue).average().orElse(0)));
                }
              }
              return new ThemeTrendPoint(e.getKey(), avgs);
            })
        .sorted(Comparator.comparing(ThemeTrendPoint::month))
        .toList();
  }

  // ---------------------------------------------------------------- verbatims

  /**
   * Anonymised quotes, optionally restricted to one theme.
   *
   * <p>Only the quote and the coarse attributes that make it interpretable are returned. No employee
   * id, name, department or designation is included, and the exit date is reduced to a month.
   */
  private List<Verbatim> verbatims(List<ExitRecord> exits, String themeFilter) {
    List<Verbatim> out = new ArrayList<>();
    for (ExitRecord x : exits) {
      if (x.verbatim() == null || x.verbatim().isBlank()) {
        continue;
      }
      String theme = null;
      String sentiment = x.overallSentiment();
      if (x.themes() != null && !x.themes().isEmpty()) {
        if (themeFilter != null && !themeFilter.isBlank()) {
          var match =
              x.themes().stream().filter(t -> themeFilter.equalsIgnoreCase(t.name())).findFirst();
          if (match.isEmpty()) {
            continue;
          }
          theme = match.get().name();
          sentiment = match.get().sentiment();
        } else {
          theme = x.themes().get(0).name();
        }
      } else if (themeFilter != null && !themeFilter.isBlank()) {
        continue;
      }
      out.add(
          new Verbatim(
              x.verbatim(),
              theme,
              sentiment,
              x.exitType(),
              x.tenureBand(),
              x.dateOfExit() == null ? null : YearMonth.from(x.dateOfExit()).toString()));
      if (out.size() >= VERBATIM_LIMIT) {
        break;
      }
    }
    return out;
  }

  private static double round(double v) {
    return Math.round(v * 10.0) / 10.0;
  }
}
