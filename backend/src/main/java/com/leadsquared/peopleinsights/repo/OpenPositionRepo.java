package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.OpenPosition;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface OpenPositionRepo extends ListCrudRepository<OpenPosition, String> {

  List<OpenPosition> findByVerticalInAndStatus(Collection<String> verticals, String status);
}
