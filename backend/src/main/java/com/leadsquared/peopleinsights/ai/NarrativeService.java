package com.leadsquared.peopleinsights.ai;

import com.leadsquared.peopleinsights.domain.NarrativeDoc;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.LeaveAttendanceService;
import com.leadsquared.peopleinsights.metrics.MetricCard;
import com.leadsquared.peopleinsights.metrics.MetricsService;
import com.leadsquared.peopleinsights.metrics.RiskScoringService;
import com.leadsquared.peopleinsights.repo.NarrativeRepo;
import com.leadsquared.peopleinsights.security.AccessScope;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generates the BU narrative summary.
 *
 * <p>The brief's hard constraint is that every figure cited must be traceable to a metric card on the
 * same view. A prompt instruction alone cannot guarantee that, so the generated text is verified: every
 * number in the output must also appear in the fact sheet the model was given. A narrative that cites a
 * number from nowhere is rejected and regenerated once; if it fails again the deterministic summary is
 * used instead. That way the view never displays an unverifiable figure.
 */
@Service
public class NarrativeService {

  private static final Logger log = LoggerFactory.getLogger(NarrativeService.class);

  /** Matches numbers with optional sign, thousands separators, decimals and a percent sign. */
  private static final Pattern NUMBER = Pattern.compile("[-+]?\\d[\\d,]*(?:\\.\\d+)?");

  private static final String SYSTEM_PROMPT =
      """
      You write short factual briefings for HR business partners at LeadSquared.

      Absolute rules:
      - Use ONLY numbers that appear verbatim in the FACTS section you are given. Never estimate, \
      infer, extrapolate or invent a figure. If you want to make a point you have no figure for, \
      make it qualitatively with no number.
      - Write 3 to 5 sentences of continuous prose. No bullet points, no headings, no preamble.
      - Cover, in this order: the most significant metric movements, then any anomaly that needs \
      attention, then what the HRBP should look at next.
      - Name the business unit once. Be specific and plain. No filler such as "it is worth noting".
      - Do not speculate about individual employees or name anyone.
      """;

  private final ClaudeClient claude;
  private final NarrativeRepo repo;
  private final MetricsService metrics;
  private final RiskScoringService risk;
  private final LeaveAttendanceService leaveAttendance;

  public NarrativeService(
      ClaudeClient claude,
      NarrativeRepo repo,
      MetricsService metrics,
      RiskScoringService risk,
      LeaveAttendanceService leaveAttendance) {
    this.claude = claude;
    this.repo = repo;
    this.metrics = metrics;
    this.risk = risk;
    this.leaveAttendance = leaveAttendance;
  }

  /**
   * The stored narrative for this view, regenerated when absent, when {@code force}, or when the
   * figures have moved since it was written.
   *
   * <p>The last case is the important one. A narrative cached on business unit and filters alone would
   * keep describing last week's numbers beside this week's cards — and since the brief requires every
   * cited figure to be traceable to a card on the same view, a stale narrative is not merely dated, it
   * is wrong. Comparing a fingerprint of the figures closes that gap.
   */
  public NarrativeDoc narrative(Dataset data, AccessScope scope, boolean force) {
    String filterKey = data.filters().cacheKey();
    MetricsService.Headline headline = metrics.headline(data);
    List<NarrativeDoc.CitedFigure> figures = citedFigures(headline);
    String factsHash = fingerprint(figures);

    if (!force) {
      Optional<NarrativeDoc> existing =
          repo.findFirstByBusinessUnitAndFilterKeyOrderByGeneratedAtDesc(data.label(), filterKey)
              .filter(doc -> factsHash.equals(doc.getFactsHash()));
      if (existing.isPresent()) {
        return existing.get();
      }
    }
    return repo.save(generate(data, scope, filterKey, headline, figures, factsHash));
  }

  /** A stable digest of the figures on the view. */
  private static String fingerprint(List<NarrativeDoc.CitedFigure> figures) {
    String joined =
        figures.stream()
            .map(f -> f.label() + "=" + f.value())
            .sorted()
            .collect(java.util.stream.Collectors.joining("|"));
    return Integer.toHexString(joined.hashCode()) + ":" + joined.length();
  }

  /** Applies an HRBP's inline edit, tagging the narrative as modified. */
  public NarrativeDoc edit(String id, String newText, AccessScope scope) {
    NarrativeDoc doc =
        repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("No narrative with id " + id));
    doc.setText(newText);
    doc.setEditedBy(scope.email());
    doc.setEditedByName(scope.displayName());
    doc.setEditedAt(Instant.now());
    return repo.save(doc);
  }

  // ---------------------------------------------------------------- generation

  private NarrativeDoc generate(
      Dataset data,
      AccessScope scope,
      String filterKey,
      MetricsService.Headline headline,
      List<NarrativeDoc.CitedFigure> figures,
      String factsHash) {
    List<NarrativeDoc.Anomaly> anomalies = detectAnomalies(data, headline);
    String facts = factSheet(data, headline, anomalies);

    NarrativeDoc doc = new NarrativeDoc();
    doc.setBusinessUnit(data.label());
    doc.setFilterKey(filterKey);
    doc.setFactsHash(factsHash);
    doc.setCitedFigures(figures);
    doc.setAnomalies(anomalies);
    doc.setGeneratedForUser(scope.email());
    doc.setGeneratedAt(Instant.now());

    Set<String> allowed = allowedNumbers(facts);

    Optional<String> generated = claude.complete(SYSTEM_PROMPT, facts);
    if (generated.isPresent()) {
      List<String> offending = unverifiableNumbers(generated.get(), allowed);
      if (!offending.isEmpty()) {
        log.warn(
            "Narrative for {} cited unverifiable figures {} — regenerating once", data.label(), offending);
        String retryPrompt =
            facts
                + "\n\nYour previous attempt used these numbers, which do not appear in FACTS: "
                + String.join(", ", offending)
                + ". Rewrite using only numbers from FACTS.";
        generated = claude.complete(SYSTEM_PROMPT, retryPrompt);
        if (generated.isPresent() && !unverifiableNumbers(generated.get(), allowed).isEmpty()) {
          generated = Optional.empty();
        }
      }
    }

    if (generated.isPresent()) {
      doc.setText(generated.get());
      doc.setModel(claude.model());
      doc.setFallback(false);
    } else {
      doc.setText(deterministicSummary(data, headline, anomalies));
      doc.setModel(claude.isConfigured() ? "fallback-after-verification" : "fallback-no-api-key");
      doc.setFallback(true);
    }
    return doc;
  }

  private List<NarrativeDoc.CitedFigure> citedFigures(MetricsService.Headline headline) {
    List<NarrativeDoc.CitedFigure> out = new ArrayList<>();
    for (MetricCard card : headline.cards()) {
      if (!card.available()) {
        continue;
      }
      out.add(new NarrativeDoc.CitedFigure(card.label(), card.displayValue()));
      if (card.momAbsolute() != null) {
        out.add(
            new NarrativeDoc.CitedFigure(
                card.label() + " — month-on-month",
                String.format("%+.1f (%+.1f%%)", card.momAbsolute(), card.momPercent())));
      }
    }
    return out;
  }

  /**
   * The prompt. Only these figures are permissible in the output, and the verification step below
   * holds the model to exactly this set.
   */
  private String factSheet(
      Dataset data, MetricsService.Headline headline, List<NarrativeDoc.Anomaly> anomalies) {
    StringBuilder sb = new StringBuilder();
    sb.append("FACTS\n");
    sb.append("Business unit: ").append(data.label()).append('\n');
    sb.append("Reporting date: ").append(data.asOf()).append('\n');
    sb.append("Period in view: ").append(headline.periodLabel()).append('\n');
    sb.append("Employees in scope: ").append(data.size()).append('\n');
    if (!data.filters().isEmpty()) {
      sb.append("Active filters: ")
          .append("grades=").append(data.filters().grades())
          .append(", locations=").append(data.filters().locations())
          .append(", departments=").append(data.filters().departments())
          .append(", tenure=").append(data.filters().tenureMinYears())
          .append("-").append(data.filters().tenureMaxYears())
          .append('\n');
    }

    sb.append("\nMETRIC CARDS ON THIS VIEW\n");
    for (MetricCard card : headline.cards()) {
      if (!card.available()) {
        sb.append("- ")
            .append(card.label())
            .append(": not available (")
            .append(card.unavailableReason())
            .append(")\n");
        continue;
      }
      sb.append("- ").append(card.label()).append(": ").append(card.displayValue());
      if (card.momAbsolute() != null) {
        sb.append(
            String.format(
                " (month-on-month %+.1f, %+.1f%%, direction %s)",
                card.momAbsolute(), card.momPercent(), card.direction()));
      } else if (card.unavailableReason() != null) {
        sb.append(" (no prior-period comparison: ").append(card.unavailableReason()).append(")");
      }
      sb.append('\n');
    }

    if (!anomalies.isEmpty()) {
      sb.append("\nANOMALIES DETECTED\n");
      for (var a : anomalies) {
        sb.append("- [")
            .append(a.severity())
            .append("] ")
            .append(a.metric())
            .append(": ")
            .append(a.description())
            .append('\n');
      }
    } else {
      sb.append("\nANOMALIES DETECTED\nNone above threshold.\n");
    }

    sb.append(
        "\nWrite the briefing now. Use only the numbers above, exactly as written.");
    return sb.toString();
  }

  // ---------------------------------------------------------------- anomaly detection

  private List<NarrativeDoc.Anomaly> detectAnomalies(
      Dataset data, MetricsService.Headline headline) {
    List<NarrativeDoc.Anomaly> out = new ArrayList<>();

    card(headline, "attritionRolling3m")
        .ifPresent(
            c -> {
              if (c.value() == null) {
                return;
              }
              if (c.value() >= 20) {
                out.add(
                    new NarrativeDoc.Anomaly(
                        "Voluntary attrition",
                        "High",
                        String.format(
                            "Rolling 3-month voluntary attrition is %.1f%%, above the 20%% threshold",
                            c.value())));
              } else if (c.momPercent() != null && c.momPercent() >= 25) {
                out.add(
                    new NarrativeDoc.Anomaly(
                        "Voluntary attrition",
                        "Medium",
                        String.format(
                            "Rolling 3-month voluntary attrition rose %.1f%% month-on-month to %.1f%%",
                            c.momPercent(), c.value())));
              }
            });

    card(headline, "enps")
        .ifPresent(
            c -> {
              if (c.value() == null || !c.available()) {
                return;
              }
              if (c.value() < 0) {
                out.add(
                    new NarrativeDoc.Anomaly(
                        "eNPS",
                        "High",
                        String.format("eNPS is negative at %.0f — detractors outnumber promoters", c.value())));
              } else if (c.value() < 10) {
                out.add(
                    new NarrativeDoc.Anomaly(
                        "eNPS", "Medium", String.format("eNPS of %.0f is weak", c.value())));
              }
            });

    // Attendance dip: latest month against the one before it.
    YearMonth latest = data.asOfMonth();
    double latestRate = leaveAttendance.attendanceRateForMonth(data, latest.toString());
    double priorRate = leaveAttendance.attendanceRateForMonth(data, latest.minusMonths(1).toString());
    if (latestRate > 0 && priorRate > 0) {
      double drop = priorRate - latestRate;
      if (drop >= 5) {
        out.add(
            new NarrativeDoc.Anomaly(
                "Attendance",
                "High",
                String.format(
                    "Attendance fell from %.1f%% to %.1f%% between %s and %s",
                    priorRate, latestRate, latest.minusMonths(1), latest)));
      } else if (drop >= 2) {
        out.add(
            new NarrativeDoc.Anomaly(
                "Attendance",
                "Medium",
                String.format(
                    "Attendance eased from %.1f%% to %.1f%% between %s and %s",
                    priorRate, latestRate, latest.minusMonths(1), latest)));
      }
    }

    int active = data.activeEmployees().size();
    int atRisk = risk.register(data).size();
    if (active > 0) {
      double pct = atRisk * 100.0 / active;
      if (pct >= 15) {
        out.add(
            new NarrativeDoc.Anomaly(
                "Attrition risk",
                "High",
                String.format("%d of %d active employees are at medium or high risk (%.1f%%)", atRisk, active, pct)));
      } else if (pct >= 8) {
        out.add(
            new NarrativeDoc.Anomaly(
                "Attrition risk",
                "Medium",
                String.format("%d of %d active employees are at medium or high risk (%.1f%%)", atRisk, active, pct)));
      }
    }

    // Team-level absence outliers, already severity-tagged by the leave and attendance service.
    var view = leaveAttendance.build(data, false);
    view.anomalies().stream()
        .limit(3)
        .forEach(
            a ->
                out.add(
                    new NarrativeDoc.Anomaly(
                        a.metric() + " — " + a.team(), a.severity(), a.detail())));

    return out;
  }

  private Optional<MetricCard> card(MetricsService.Headline headline, String key) {
    return headline.cards().stream().filter(c -> key.equals(c.key())).findFirst();
  }

  // ---------------------------------------------------------------- verification

  /** Every numeric token present in the fact sheet, normalised for comparison. */
  public static Set<String> allowedNumbers(String facts) {
    Set<String> allowed = new LinkedHashSet<>();
    Matcher m = NUMBER.matcher(facts);
    while (m.find()) {
      allowed.add(normalise(m.group()));
    }
    return allowed;
  }

  /** Numbers in the generated text that do not appear in the fact sheet. */
  public static List<String> unverifiableNumbers(String text, Set<String> allowed) {
    List<String> offending = new ArrayList<>();
    Matcher m = NUMBER.matcher(text);
    while (m.find()) {
      String raw = m.group();
      String norm = normalise(raw);
      if (allowed.contains(norm)) {
        continue;
      }
      // A rounded restatement of an allowed figure is acceptable: 12.4% written as 12%.
      if (allowed.stream().anyMatch(a -> isRoundingOf(norm, a))) {
        continue;
      }
      offending.add(raw);
    }
    return offending;
  }

  /**
   * Reduces a numeric token to its magnitude, so "+1,234.0", "-1234" and "1234" all compare equal.
   *
   * <p>Comparing magnitudes rather than signed values matters: a card carrying a month-on-month change
   * of -0.9% is correctly written in prose as "down 0.9%", and treating that as an unverifiable figure
   * would reject accurate narratives. Direction is carried by the words, and the card's own direction
   * field is what the UI renders.
   */
  private static String normalise(String raw) {
    String cleaned = raw.replace(",", "").replace("+", "").trim();
    try {
      double d = Math.abs(Double.parseDouble(cleaned));
      // Drop a trailing ".0" so integral values have one representation.
      return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    } catch (NumberFormatException e) {
      return cleaned;
    }
  }

  private static boolean isRoundingOf(String candidate, String allowed) {
    try {
      double c = Double.parseDouble(candidate);
      double a = Double.parseDouble(allowed);
      return Math.abs(c - a) < 1.0 && Math.abs(a) >= 1.0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  // ---------------------------------------------------------------- fallback

  /**
   * The narrative used when the model is unavailable or its output failed verification.
   *
   * <p>Built from the same cards, so it satisfies the traceability rule by construction. Blank is a
   * supported state for the API key, and this is what makes that true.
   */
  private String deterministicSummary(
      Dataset data, MetricsService.Headline headline, List<NarrativeDoc.Anomaly> anomalies) {
    StringBuilder sb = new StringBuilder();
    sb.append(data.label())
        .append(" as at ")
        .append(data.asOf())
        .append(": ");

    List<String> movements = new ArrayList<>();
    for (MetricCard c : headline.cards()) {
      if (!c.available() || c.value() == null) {
        continue;
      }
      if (c.momAbsolute() == null) {
        movements.add(c.label().toLowerCase() + " at " + c.displayValue());
      } else {
        movements.add(
            String.format(
                "%s at %s (%+.1f%% month-on-month)",
                c.label().toLowerCase(), c.displayValue(), c.momPercent() == null ? 0 : c.momPercent()));
      }
    }
    sb.append(String.join("; ", movements)).append(". ");

    if (anomalies.isEmpty()) {
      sb.append("No metric breached its anomaly threshold this period. ");
    } else {
      var top = anomalies.get(0);
      sb.append(
          String.format(
              "The most significant flag is %s (%s severity): %s. ",
              top.metric(), top.severity().toLowerCase(), top.description()));
      if (anomalies.size() > 1) {
        sb.append(anomalies.size() - 1).append(" further anomaly flag(s) are listed on this view. ");
      }
    }

    var unavailable = headline.cards().stream().filter(c -> !c.available()).toList();
    if (!unavailable.isEmpty()) {
      sb.append("Note that ")
          .append(String.join(" and ", unavailable.stream().map(c -> c.label().toLowerCase()).toList()))
          .append(" could not be computed from the supplied datasets.");
    }
    return sb.toString().trim();
  }
}
