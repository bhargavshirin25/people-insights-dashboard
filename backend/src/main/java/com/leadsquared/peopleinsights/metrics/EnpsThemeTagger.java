package com.leadsquared.peopleinsights.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assigns themes to eNPS verbatim comments.
 *
 * <p>The exit dataset arrives with NLP themes already attached; the eNPS dataset does not — it has
 * free text only. Rather than send 5,000 comments to a model on every page load, this is a
 * deterministic keyword tagger over the same theme vocabulary the exit data uses, so the two views
 * are comparable and the tagging is reproducible and auditable.
 *
 * <p>It is a heuristic, and the UI labels it as one. A comment matching no vocabulary is tagged
 * "Uncategorised" rather than forced into the nearest theme.
 */
final class EnpsThemeTagger {

  static final String UNCATEGORISED = "Uncategorised";

  /** Theme -> lowercase keywords. Ordered so the most specific themes are checked first. */
  private static final Map<String, List<String>> VOCABULARY = new LinkedHashMap<>();

  static {
    VOCABULARY.put(
        "Compensation & Benefits",
        List.of("salary", "pay", "compensation", "ctc", "increment", "hike", "bonus", "appraisal amount", "underpaid", "benefits"));
    VOCABULARY.put(
        "Career Growth & Development",
        List.of("growth", "career", "promotion", "progression", "learning", "development", "upskill", "training", "stagnant", "no scope"));
    VOCABULARY.put(
        "Manager & Leadership",
        List.of("manager", "leadership", "supervisor", "management style", "micromanage", "my lead", "senior leadership", "favouritism", "favoritism"));
    VOCABULARY.put(
        "Work-Life Balance",
        List.of("work-life", "work life", "balance", "long hours", "weekend", "overtime", "burnout", "burn out", "workload", "late night"));
    VOCABULARY.put(
        "Company Culture",
        List.of("culture", "values", "politics", "transparency", "inclusive", "toxic", "respect", "environment"));
    VOCABULARY.put(
        "Role & Job Content",
        List.of("role", "job content", "repetitive", "monotonous", "responsibilities", "challenging work", "boring", "meaningful work"));
    VOCABULARY.put(
        "Recognition",
        List.of("recognition", "recognised", "recognized", "appreciated", "acknowledge", "credit", "valued"));
    VOCABULARY.put(
        "Team & Collaboration",
        List.of("team", "colleague", "collaboration", "peers", "cross-functional", "silo"));
    VOCABULARY.put(
        "Tools & Process",
        List.of("tools", "process", "system", "bureaucracy", "approval", "infrastructure", "technology"));
    VOCABULARY.put(
        "Flexibility & Location",
        List.of("remote", "hybrid", "wfh", "work from home", "commute", "office location", "relocat", "flexib"));
  }

  private EnpsThemeTagger() {}

  /** All themes matched by the comment, or a single "Uncategorised" entry. */
  static List<String> themesFor(String comment) {
    if (comment == null || comment.isBlank()) {
      return List.of(UNCATEGORISED);
    }
    String text = comment.toLowerCase(Locale.ENGLISH);
    List<String> matched = new ArrayList<>();
    for (var entry : VOCABULARY.entrySet()) {
      for (String keyword : entry.getValue()) {
        if (text.contains(keyword)) {
          matched.add(entry.getKey());
          break;
        }
      }
    }
    return matched.isEmpty() ? List.of(UNCATEGORISED) : matched;
  }

  static List<String> vocabulary() {
    return List.copyOf(VOCABULARY.keySet());
  }
}
