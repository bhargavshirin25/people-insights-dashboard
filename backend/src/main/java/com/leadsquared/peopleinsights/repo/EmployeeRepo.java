package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.Employee;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Employee master access.
 *
 * <p>Every finder is BU-parameterised on purpose. Nothing outside {@code ScopeGuard} decides which
 * BU list may be passed, so there is no way to read the collection without a BU predicate.
 */
public interface EmployeeRepo extends ListCrudRepository<Employee, String> {

  List<Employee> findByVerticalIn(Collection<String> verticals);

  List<Employee> findByVerticalInAndStatus(Collection<String> verticals, String status);

  long countByVerticalInAndStatus(Collection<String> verticals, String status);

  Optional<Employee> findByEmailIgnoreCase(String email);

  List<Employee> findByEmployeeIdIn(Collection<String> ids);
}
