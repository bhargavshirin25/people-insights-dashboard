package com.leadsquared.peopleinsights.export;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.domain.NarrativeDoc;
import com.leadsquared.peopleinsights.metrics.CalendarService;
import com.leadsquared.peopleinsights.metrics.Dataset;
import com.leadsquared.peopleinsights.metrics.ExitAnalyticsService;
import com.leadsquared.peopleinsights.metrics.MetricCard;
import com.leadsquared.peopleinsights.metrics.MetricsService;
import com.leadsquared.peopleinsights.metrics.RiskScoringService;
import com.leadsquared.peopleinsights.security.AccessScope;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

/**
 * One-click business review deck.
 *
 * <p>Scoped to the exporting user's role by construction: the {@link Dataset} handed in has already
 * passed the scope guard, and the at-risk section carries a count only — never a name — because the
 * export leaves the application and the rules forbid individual PII in a shared artefact. The
 * confidentiality label is stamped on every page.
 */
@Service
public class DeckExportService {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

  private final AppProperties props;
  private final MetricsService metrics;
  private final ExitAnalyticsService exitAnalytics;
  private final RiskScoringService risk;
  private final CalendarService calendar;

  public DeckExportService(
      AppProperties props,
      MetricsService metrics,
      ExitAnalyticsService exitAnalytics,
      RiskScoringService risk,
      CalendarService calendar) {
    this.props = props;
    this.metrics = metrics;
    this.exitAnalytics = exitAnalytics;
    this.risk = risk;
    this.calendar = calendar;
  }

  public byte[] renderPdf(Dataset data, NarrativeDoc narrative, AccessScope scope) {
    String html = buildHtml(data, narrative, scope);
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PdfRendererBuilder builder = new PdfRendererBuilder();
      builder.useFastMode();
      builder.withHtmlContent(html, null);
      builder.toStream(out);
      builder.run();
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to render the business review deck", e);
    }
  }

  public String fileName(Dataset data) {
    return "business-review-"
        + data.label().toLowerCase().replaceAll("[^a-z0-9]+", "-")
        + "-"
        + data.asOf()
        + ".pdf";
  }

  private String buildHtml(Dataset data, NarrativeDoc narrative, AccessScope scope) {
    MetricsService.Headline headline = metrics.headline(data);
    var exitView = exitAnalytics.build(data, null);
    var register = risk.register(data);
    var calendarView = calendar.build(data, scope.canSeeIndividualPii());

    long high = register.stream().filter(RiskScoringService.Assessment::isHigh).count();

    StringBuilder sb = new StringBuilder();
    sb.append("<html><head><meta charset='utf-8'/><style>")
        .append(css())
        .append("</style></head><body>");

    // Cover
    sb.append("<div class='label'>").append(esc(props.getConfidentialityLabel())).append("</div>");
    sb.append("<h1>Business Review — ").append(esc(data.label())).append("</h1>");
    sb.append("<p class='sub'>Reporting date ")
        .append(data.asOf().format(DATE))
        .append(" &#183; ")
        .append(esc(headline.periodLabel()))
        .append(" &#183; ")
        .append(data.size())
        .append(" employees in scope</p>");
    sb.append("<p class='sub'>Prepared by ")
        .append(esc(scope.displayName() == null ? scope.email() : scope.displayName()))
        .append(" (")
        .append(esc(scope.role().name()))
        .append(")</p>");

    // Metric snapshot
    sb.append("<h2>Key metrics</h2><table><thead><tr>")
        .append("<th>Metric</th><th>Value</th><th>MoM change</th><th>Direction</th><th>Basis</th>")
        .append("</tr></thead><tbody>");
    for (MetricCard c : headline.cards()) {
      sb.append("<tr><td>").append(esc(c.label())).append("</td>");
      if (!c.available()) {
        sb.append("<td colspan='4' class='muted'>Not available — ")
            .append(esc(c.unavailableReason()))
            .append("</td></tr>");
        continue;
      }
      sb.append("<td class='num'>").append(esc(c.displayValue())).append("</td>");
      sb.append("<td class='num'>")
          .append(
              c.momAbsolute() == null
                  ? "—"
                  : String.format("%+.1f (%+.1f%%)", c.momAbsolute(), c.momPercent()))
          .append("</td>");
      sb.append("<td class='s-").append(c.sentiment()).append("'>")
          .append(arrow(c.direction()))
          .append(" ")
          .append(c.sentiment())
          .append("</td>");
      sb.append("<td class='muted small'>").append(esc(c.basis())).append("</td></tr>");
    }
    sb.append("</tbody></table>");

    // Narrative
    sb.append("<h2>AI narrative summary</h2>");
    sb.append("<div class='narrative'>").append(esc(narrative.getText())).append("</div>");
    if (narrative.getEditedBy() != null) {
      sb.append("<p class='muted small'>Modified by ")
          .append(esc(narrative.getEditedByName() == null ? narrative.getEditedBy() : narrative.getEditedByName()))
          .append("</p>");
    } else if (narrative.isFallback()) {
      sb.append("<p class='muted small'>Generated without the language model; figures taken directly from the metric cards.</p>");
    } else {
      sb.append("<p class='muted small'>Generated by ").append(esc(narrative.getModel()))
          .append("; every figure verified against the metric cards above.</p>");
    }
    if (!narrative.getAnomalies().isEmpty()) {
      sb.append("<table><thead><tr><th>Anomaly</th><th>Severity</th><th>Detail</th></tr></thead><tbody>");
      for (var a : narrative.getAnomalies()) {
        sb.append("<tr><td>").append(esc(a.metric())).append("</td>")
            .append("<td class='sev-").append(a.severity().toLowerCase()).append("'>")
            .append(esc(a.severity())).append("</td>")
            .append("<td class='small'>").append(esc(a.description())).append("</td></tr>");
      }
      sb.append("</tbody></table>");
    }

    // Exit themes
    sb.append("<h2>Top exit themes and sentiment</h2>");
    if (exitView.topThemes().isEmpty()) {
      sb.append("<p class='muted'>No exits in scope for this selection.</p>");
    } else {
      sb.append("<table><thead><tr><th>Theme</th><th>Mentions</th><th>% of exits</th>")
          .append("<th>Avg sentiment</th><th>Dominant</th></tr></thead><tbody>");
      exitView.topThemes().stream()
          .limit(8)
          .forEach(
              t ->
                  sb.append("<tr><td>").append(esc(t.theme())).append("</td>")
                      .append("<td class='num'>").append(t.mentions()).append("</td>")
                      .append("<td class='num'>").append(String.format("%.1f%%", t.pctOfExits())).append("</td>")
                      .append("<td class='num'>").append(String.format("%.0f", t.avgScore())).append("</td>")
                      .append("<td>").append(esc(t.sentiment())).append("</td></tr>"));
      sb.append("</tbody></table>");
      sb.append("<p class='muted small'>")
          .append(exitView.totalExits()).append(" exits, of which ")
          .append(exitView.voluntaryExits()).append(" voluntary and ")
          .append(exitView.regrettableExits()).append(" regrettable. Average tenure at exit ")
          .append(String.format("%.1f", exitView.avgTenureMonths())).append(" months.</p>");
    }

    // At-risk: counts only.
    sb.append("<h2>Attrition risk</h2>");
    sb.append("<p>")
        .append(register.size())
        .append(" employees at medium or high risk, of which ")
        .append(high)
        .append(" high risk.</p>");
    sb.append("<p class='muted small'>Individual names are withheld from exported decks. The named "
        + "register is available in the dashboard to the assigned HRBP and to HR Head and CHRO.</p>");

    // Calendar
    sb.append("<h2>HR calendar — next 30 days</h2>");
    if (calendarView.totalEvents() == 0) {
      sb.append("<p class='muted'>No people events fall in the next 30 days for this selection.</p>");
    } else if (!calendarView.eventsVisible()) {
      // Aggregate-only role: the counts, and nothing that points at a person.
      sb.append("<table><thead><tr><th>Event type</th><th>Count</th></tr></thead><tbody>");
      calendarView
          .typeCounts()
          .forEach(
              (type, count) ->
                  sb.append("<tr><td>")
                      .append(esc(calendarView.typeLabels().getOrDefault(type, type)))
                      .append("</td><td>")
                      .append(count)
                      .append("</td></tr>"));
      sb.append("</tbody></table>");
      sb.append("<p class='muted small'>")
          .append(esc(calendarView.eventsNote()))
          .append("</p>");
    } else {
      sb.append("<table><thead><tr><th>Date</th><th>Type</th><th>Event</th></tr></thead><tbody>");
      calendarView.events().stream()
          .limit(25)
          .forEach(
              e ->
                  sb.append("<tr><td>").append(e.date().format(DATE)).append("</td>")
                      .append("<td>").append(esc(e.typeLabel())).append("</td>")
                      .append("<td class='small'>").append(esc(e.title())).append("</td></tr>"));
      sb.append("</tbody></table>");
      if (calendarView.events().size() > 25) {
        sb.append("<p class='muted small'>")
            .append(calendarView.events().size() - 25)
            .append(" further events are listed in the dashboard.</p>");
      }
    }

    sb.append("<div class='footer'>").append(esc(props.getConfidentialityLabel())).append("</div>");
    sb.append("</body></html>");
    return sb.toString();
  }

  /**
   * Direction glyphs as numeric character references.
   *
   * <p>The renderer parses the document as strict XML, where the only declared entities are the five
   * XML predefined ones. A named HTML entity aborts the whole render, so every non-ASCII glyph in this
   * document is written as a numeric reference.
   */
  private static String arrow(String direction) {
    return switch (direction == null ? "flat" : direction) {
      case "up" -> "&#9650;";
      case "down" -> "&#9660;";
      default -> "&#9644;";
    };
  }

  private static String css() {
    return """
        @page { size: A4; margin: 18mm 14mm; }
        body { font-family: Helvetica, Arial, sans-serif; color: #1a1a1a; font-size: 10pt; }
        h1 { font-size: 20pt; margin: 4mm 0 1mm; }
        h2 { font-size: 12pt; margin: 7mm 0 2mm; padding-bottom: 1mm; border-bottom: 0.4mm solid #d0d0d0; }
        p { margin: 1.5mm 0; line-height: 1.45; }
        .label { font-size: 7.5pt; letter-spacing: 0.4pt; text-transform: uppercase; color: #a12828;
                 border: 0.3mm solid #a12828; padding: 1mm 2mm; display: inline-block; }
        .footer { margin-top: 8mm; padding-top: 2mm; border-top: 0.3mm solid #d0d0d0;
                  font-size: 7.5pt; color: #a12828; text-transform: uppercase; letter-spacing: 0.4pt; }
        .sub { color: #555; font-size: 9pt; }
        .muted { color: #666; }
        .small { font-size: 8pt; }
        .narrative { background: #f5f7fa; border-left: 1mm solid #2f5fd0; padding: 3mm 4mm;
                     line-height: 1.55; font-size: 10.5pt; }
        table { width: 100%; border-collapse: collapse; margin: 2mm 0 3mm; }
        th { text-align: left; background: #eef1f6; font-size: 8.5pt; padding: 1.6mm 2mm;
             border-bottom: 0.3mm solid #c8cfdb; }
        td { padding: 1.5mm 2mm; border-bottom: 0.2mm solid #e6e6e6; vertical-align: top; }
        .num { text-align: right; font-variant-numeric: tabular-nums; }
        .s-green { color: #1c7c3f; } .s-amber { color: #a86a08; } .s-red { color: #b3261e; }
        .sev-high { color: #b3261e; font-weight: bold; }
        .sev-medium { color: #a86a08; }
        .sev-low { color: #555; }
        """;
  }

  /** Escapes text for HTML. Employee-supplied verbatims flow through here. */
  private static String esc(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
