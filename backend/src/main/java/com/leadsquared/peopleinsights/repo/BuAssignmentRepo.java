package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.BuAssignment;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface BuAssignmentRepo extends ListCrudRepository<BuAssignment, String> {

  Optional<BuAssignment> findByHrbpEmailIgnoreCase(String email);
}
