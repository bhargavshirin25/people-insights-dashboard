package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.ExitRecord;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface ExitRepo extends ListCrudRepository<ExitRecord, String> {

  List<ExitRecord> findByVerticalIn(Collection<String> verticals);
}
