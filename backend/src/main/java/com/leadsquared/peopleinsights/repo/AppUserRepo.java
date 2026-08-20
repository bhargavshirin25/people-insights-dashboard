package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.AppUser;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface AppUserRepo extends ListCrudRepository<AppUser, String> {

  Optional<AppUser> findByEmailIgnoreCase(String email);
}
