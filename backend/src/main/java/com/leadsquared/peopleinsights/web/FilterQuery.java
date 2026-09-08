package com.leadsquared.peopleinsights.web;

import com.leadsquared.peopleinsights.metrics.FilterSpec;
import java.time.LocalDate;
import java.util.List;

/**
 * Query-parameter shape for the multi-level filters, bound on every view endpoint.
 *
 * <p>Notably it does not carry the BU. That arrives separately and goes through the scope guard, so a
 * filter object can never be the thing that widens what a caller sees.
 */
public class FilterQuery {

  private List<String> grade;
  private List<String> location;
  private List<String> department;
  private Double tenureMin;
  private Double tenureMax;
  private String period;
  private LocalDate from;
  private LocalDate to;

  public FilterSpec toSpec() {
    return new FilterSpec(
            grade == null ? List.of() : grade,
            location == null ? List.of() : location,
            department == null ? List.of() : department,
            tenureMin,
            tenureMax,
            period,
            from,
            to)
        .normalised();
  }

  public List<String> getGrade() {
    return grade;
  }

  public void setGrade(List<String> grade) {
    this.grade = grade;
  }

  public List<String> getLocation() {
    return location;
  }

  public void setLocation(List<String> location) {
    this.location = location;
  }

  public List<String> getDepartment() {
    return department;
  }

  public void setDepartment(List<String> department) {
    this.department = department;
  }

  public Double getTenureMin() {
    return tenureMin;
  }

  public void setTenureMin(Double tenureMin) {
    this.tenureMin = tenureMin;
  }

  public Double getTenureMax() {
    return tenureMax;
  }

  public void setTenureMax(Double tenureMax) {
    this.tenureMax = tenureMax;
  }

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String period) {
    this.period = period;
  }

  public LocalDate getFrom() {
    return from;
  }

  public void setFrom(LocalDate from) {
    this.from = from;
  }

  public LocalDate getTo() {
    return to;
  }

  public void setTo(LocalDate to) {
    this.to = to;
  }
}
