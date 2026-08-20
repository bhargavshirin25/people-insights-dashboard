package com.leadsquared.peopleinsights.metrics;

import com.leadsquared.peopleinsights.config.AppProperties;
import com.leadsquared.peopleinsights.repo.IngestRunRepo;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * Resolves the date the dashboard treats as "now".
 *
 * <p>Every period-relative metric is anchored here rather than on the wall clock. The HR Ops extract
 * ends 31-Jul-2026, so a wall-clock anchor would make "last 30 days" empty and every month-on-month
 * comparison meaningless. Configuration wins; otherwise the anchor is the latest date the most recent
 * ingest actually found.
 */
@Service
public class AsOfService {

  private final AppProperties props;
  private final IngestRunRepo ingestRuns;

  public AsOfService(AppProperties props, IngestRunRepo ingestRuns) {
    this.props = props;
    this.ingestRuns = ingestRuns;
  }

  public LocalDate asOf() {
    if (props.getAsOfDate() != null) {
      return props.getAsOfDate();
    }
    return ingestRuns
        .findFirstByStatusOrderByFinishedAtDesc("SUCCESS")
        .map(run -> run.dataAsOfDate() == null ? null : LocalDate.parse(run.dataAsOfDate()))
        .orElse(LocalDate.now());
  }

  /** Where the data actually ends, for the "data as of" stamp in the UI and on exports. */
  public String dataAsOfLabel() {
    return ingestRuns
        .findFirstByStatusOrderByFinishedAtDesc("SUCCESS")
        .map(run -> run.dataAsOfDate())
        .orElse(null);
  }
}
