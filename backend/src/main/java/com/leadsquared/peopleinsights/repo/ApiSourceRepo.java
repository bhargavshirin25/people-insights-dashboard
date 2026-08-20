package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.ApiSource;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface ApiSourceRepo extends ListCrudRepository<ApiSource, String> {

  Optional<ApiSource> findByNameIgnoreCase(String name);
}
