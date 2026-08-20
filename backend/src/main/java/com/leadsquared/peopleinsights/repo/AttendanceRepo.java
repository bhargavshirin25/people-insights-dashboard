package com.leadsquared.peopleinsights.repo;

import com.leadsquared.peopleinsights.domain.AttendanceMonth;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

public interface AttendanceRepo extends ListCrudRepository<AttendanceMonth, String> {

  List<AttendanceMonth> findByVerticalIn(Collection<String> verticals);

  List<AttendanceMonth> findByVerticalInAndYearMonthIn(
      Collection<String> verticals, Collection<String> yearMonths);

  List<AttendanceMonth> findByEmployeeIdIn(Collection<String> ids);

  List<AttendanceMonth> findByEmployeeId(String employeeId);
}
