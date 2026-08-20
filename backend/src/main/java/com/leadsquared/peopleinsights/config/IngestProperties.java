package com.leadsquared.peopleinsights.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Location and file names of the HR Ops workbooks. */
@ConfigurationProperties(prefix = "ingest")
public class IngestProperties {

  private String sourceDirectory = "";
  private String employeeMasterFile = "Dummy_Employee_Master_Data.xlsx";
  private String compensationFile = "Compensation_Records.xlsx";
  private String leaveFile = "Employee_Leave_Records.xlsx";
  private String attendanceFile = "Attendance_Records.xlsx";
  private String enpsFile = "NPS_Data.xlsx";
  private String exitFile = "Exit_Data.xlsx";

  /** Drop and rewrite the people collections on each run. The workbooks are a full snapshot. */
  private boolean replaceExisting = true;

  public String getSourceDirectory() {
    return sourceDirectory;
  }

  public void setSourceDirectory(String sourceDirectory) {
    this.sourceDirectory = sourceDirectory;
  }

  public String getEmployeeMasterFile() {
    return employeeMasterFile;
  }

  public void setEmployeeMasterFile(String employeeMasterFile) {
    this.employeeMasterFile = employeeMasterFile;
  }

  public String getCompensationFile() {
    return compensationFile;
  }

  public void setCompensationFile(String compensationFile) {
    this.compensationFile = compensationFile;
  }

  public String getLeaveFile() {
    return leaveFile;
  }

  public void setLeaveFile(String leaveFile) {
    this.leaveFile = leaveFile;
  }

  public String getAttendanceFile() {
    return attendanceFile;
  }

  public void setAttendanceFile(String attendanceFile) {
    this.attendanceFile = attendanceFile;
  }

  public String getEnpsFile() {
    return enpsFile;
  }

  public void setEnpsFile(String enpsFile) {
    this.enpsFile = enpsFile;
  }

  public String getExitFile() {
    return exitFile;
  }

  public void setExitFile(String exitFile) {
    this.exitFile = exitFile;
  }

  public boolean isReplaceExisting() {
    return replaceExisting;
  }

  public void setReplaceExisting(boolean replaceExisting) {
    this.replaceExisting = replaceExisting;
  }
}
