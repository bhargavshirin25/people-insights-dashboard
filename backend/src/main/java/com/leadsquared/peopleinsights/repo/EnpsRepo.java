package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.EnpsResponse;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface EnpsRepo extends ListCrudRepository<EnpsResponse, String> {

  List<EnpsResponse> findByVerticalIn(Collection<String> verticals);

  List<EnpsResponse> findByVerticalInAndCycle(Collection<String> verticals, String cycle);

  List<EnpsResponse> findByEmployeeIdIn(Collection<String> ids);
}
