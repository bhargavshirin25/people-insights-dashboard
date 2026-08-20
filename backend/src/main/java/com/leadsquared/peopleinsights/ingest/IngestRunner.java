package com.leadsquared.peopleinsights.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs the workbook load when the application is started with {@code --ingest}, then exits unless
 * {@code --ingest-and-serve} was passed. Keeping ingestion off the normal startup path means a
 * restart never silently rewrites the data layer.
 */
@Component
@Order(2)
public class IngestRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

  private final IngestService service;

  public IngestRunner(IngestService service) {
    this.service = service;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean ingest = args.containsOption("ingest") || args.containsOption("ingest-and-serve");
    if (!ingest) {
      return;
    }
    var run = service.run();
    log.info("Ingest run {} finished with status {}", run.id(), run.status());
    if (!run.warnings().isEmpty()) {
      log.warn("{} ingest warnings; first few:", run.warnings().size());
      run.warnings().stream().limit(10).forEach(w -> log.warn("  - {}", w));
    }
    if (!args.containsOption("ingest-and-serve")) {
      log.info("Ingest-only run complete; shutting down.");
      System.exit(0);
    }
  }
}
