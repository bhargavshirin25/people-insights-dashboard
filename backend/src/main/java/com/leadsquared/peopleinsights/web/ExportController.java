package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.ai.NarrativeService;
import com.leadsquared.peopleinsights.export.DeckExportService;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.DatasetLoader;
import com.leadsquared.peopleinsights.security.AuditService;
import com.leadsquared.peopleinsights.security.ScopeGuard;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One-click business review deck export.
 *
 * <p>Scoped by the same guard as every other view, and logged as its own audit event type so an export
 * is distinguishable from an on-screen read when the trail is reviewed.
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

  private final ScopeGuard guard;
  private final DatasetLoader loader;
  private final NarrativeService narratives;
  private final DeckExportService deck;
  private final AuditService audit;

  public ExportController(
      ScopeGuard guard,
      DatasetLoader loader,
      NarrativeService narratives,
      DeckExportService deck,
      AuditService audit) {
    this.guard = guard;
    this.loader = loader;
    this.narratives = narratives;
    this.deck = deck;
    this.audit = audit;
  }

  @GetMapping("/deck")
  public ResponseEntity<byte[]> exportDeck(
      @RequestParam(required = false) String bu, @ModelAttribute FilterQuery filters) {
    var scoped = guard.resolve(bu, "EXPORT", "EXPORT_BUSINESS_REVIEW_DECK");
    Dataset data = loader.load(scoped, filters.toSpec());

    // Reuse the narrative already on screen rather than generating a different one for the deck.
    var narrative = narratives.narrative(data, scoped.scope(), false);
    byte[] pdf = deck.renderPdf(data, narrative, scoped.scope());

    audit.granted(
        scoped.scope(),
        scoped.label(),
        "EXPORT",
        "EXPORT_COMPLETED",
        "PDF business review deck, " + pdf.length + " bytes");

    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(deck.fileName(data)).build().toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(pdf);
  }
}
