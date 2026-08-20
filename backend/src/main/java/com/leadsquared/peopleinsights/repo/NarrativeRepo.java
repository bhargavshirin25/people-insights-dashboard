package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.NarrativeDoc;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface NarrativeRepo extends ListCrudRepository<NarrativeDoc, String> {

  Optional<NarrativeDoc> findFirstByBusinessUnitAndFilterKeyOrderByGeneratedAtDesc(
      String businessUnit, String filterKey);
}
