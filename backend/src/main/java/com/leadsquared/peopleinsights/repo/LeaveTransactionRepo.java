package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.LeaveTransaction;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface LeaveTransactionRepo extends ListCrudRepository<LeaveTransaction, String> {

  List<LeaveTransaction> findByVerticalIn(Collection<String> verticals);

  List<LeaveTransaction> findByEmployeeIdIn(Collection<String> ids);
}
