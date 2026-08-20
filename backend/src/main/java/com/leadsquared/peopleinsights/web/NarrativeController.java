package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.ai.NarrativeService;
import com.leadsquared.peopleinsights.domain.NarrativeDoc;
import com.leadsquared.peopleinsights.metrics.DatasetLoader;
import com.leadsquared.peopleinsights.security.ScopeGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Regenerate and edit the AI narrative summary. */
@RestController
@RequestMapping("/api/narrative")
public class NarrativeController {

  private final ScopeGuard guard;
  private final DatasetLoader loader;
  private final NarrativeService narratives;

  public NarrativeController(
      ScopeGuard guard, DatasetLoader loader, NarrativeService narratives) {
    this.guard = guard;
    this.loader = loader;
    this.narratives = narratives;
  }

  /**
   * The narrative for the current view, generated on first request for a BU and filter combination
   * and reused afterwards. Fetched separately from the metric cards so the cards never wait on a
   * language-model call.
   */
  @GetMapping
  public NarrativeDoc current(
      @RequestParam(required = false) String bu, @ModelAttribute FilterQuery filters) {
    var scoped = guard.resolve(bu, "NARRATIVE", "VIEW_NARRATIVE");
    return narratives.narrative(loader.load(scoped, filters.toSpec()), scoped.scope(), false);
  }

  /** Forces a fresh generation for the current BU and filter combination. */
  @PostMapping("/regenerate")
  public NarrativeDoc regenerate(
      @RequestParam(required = false) String bu, @ModelAttribute FilterQuery filters) {
    var scoped = guard.resolve(bu, "NARRATIVE", "REGENERATE_NARRATIVE");
    return narratives.narrative(loader.load(scoped, filters.toSpec()), scoped.scope(), true);
  }

  public record EditRequest(String id, String text) {}

  /**
   * Applies an inline edit before the narrative goes into a business review deck. The result is tagged
   * with the editor so a reader can tell curated text from generated text.
   */
  @PostMapping("/edit")
  public NarrativeDoc edit(@RequestBody EditRequest request) {
    var scope = guard.currentScope();
    if (request == null || request.id() == null || request.text() == null || request.text().isBlank()) {
      throw new IllegalArgumentException("A narrative id and replacement text are required.");
    }
    return narratives.edit(request.id(), request.text().trim(), scope);
  }
}
