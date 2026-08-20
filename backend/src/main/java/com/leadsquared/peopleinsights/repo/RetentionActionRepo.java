package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.RetentionAction;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface RetentionActionRepo extends ListCrudRepository<RetentionAction, String> {

  List<RetentionAction> findByEmployeeIdOrderByLoggedAtDesc(String employeeId);

  List<RetentionAction> findByVerticalInOrderByLoggedAtDesc(Collection<String> verticals);

  List<RetentionAction> findByEmployeeIdIn(Collection<String> ids);
}
