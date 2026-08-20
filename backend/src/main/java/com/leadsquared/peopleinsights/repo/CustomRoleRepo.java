package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.CustomRole;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface CustomRoleRepo extends ListCrudRepository<CustomRole, String> {

  Optional<CustomRole> findByNameIgnoreCase(String name);
}
