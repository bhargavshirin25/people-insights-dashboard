package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.Compensation;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

/** Compensation access. Individual rows are gated by role before any of these are called. */
public interface CompensationRepo extends ListCrudRepository<Compensation, String> {

  List<Compensation> findByVerticalIn(Collection<String> verticals);

  List<Compensation> findByEmployeeIdIn(Collection<String> ids);
}
