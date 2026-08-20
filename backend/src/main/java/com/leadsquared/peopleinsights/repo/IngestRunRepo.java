package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.IngestRun;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface IngestRunRepo extends ListCrudRepository<IngestRun, String> {

  Optional<IngestRun> findFirstByStatusOrderByFinishedAtDesc(String status);
}
