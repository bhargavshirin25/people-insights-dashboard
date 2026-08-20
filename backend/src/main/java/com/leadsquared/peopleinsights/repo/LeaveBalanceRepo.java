package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.LeaveBalance;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface LeaveBalanceRepo extends ListCrudRepository<LeaveBalance, String> {

  List<LeaveBalance> findByVerticalIn(Collection<String> verticals);

  List<LeaveBalance> findByEmployeeIdIn(Collection<String> ids);
}
