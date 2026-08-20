package com.leadsquared.peopleinsights.repo;

import java.util.List;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Component;

/**
 * Explicit inserts for the immutable records.
 *
 * <p>An audit event, a retention action, an open position and an ingest run all arrive with an id the
 * application generated, and none of them is ever updated. {@code save()} on a record cannot tell that
 * situation from an update — there is no flag to read, unlike the mutable aggregates — and would issue
 * an UPDATE matching no row. Calling {@code insert} states the intent instead of encoding it in a
 * convention, and a duplicate id then fails loudly rather than silently doing nothing.
 */
@Component
public class Store {

  private final JdbcAggregateTemplate template;

  public Store(JdbcAggregateTemplate template) {
    this.template = template;
  }

  public <T> T insert(T entity) {
    return template.insert(entity);
  }

  /** Inserts a batch one row at a time; used by ingest and migration, which clear the table first. */
  public <T> void insertAll(List<T> entities) {
    for (T entity : entities) {
      template.insert(entity);
    }
  }

  public <T> T update(T entity) {
    return template.update(entity);
  }
}
