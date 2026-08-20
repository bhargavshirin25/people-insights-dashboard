package com.leadsquared.peopleinsights.domain;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Attendance Data (dataset 4), reshaped from the wide source sheet.
 *
 * <p>The source is 5,000 rows x 181 day-columns (Feb-Jul 2026). Storing one document per day
 * would be ~905k documents for metrics that are always read per employee-month, so each document
 * holds one employee-month: the day marks are kept verbatim for drill-down while the counts that
 * every dashboard view needs are precomputed at ingest.
 */
@Table("attendance_months")
public record AttendanceMonth(
    @Id String id,
    String employeeId,
    String fullName,
    String vertical,
    String department,
    String grade,
    String location,
    int year,
    int month,
    /** "2026-07" — sortable and range-queryable. */
    @Column("year_month_key") String yearMonth,
    int presentDays,
    int absentDays,
    int lopDays,
    int leaveDays,
    int halfDays,
    int weekOffDays,
    int singlePunchDays,
    int regularisationRequests,
    /** Calendar days in the month excluding week offs. */
    int workingDays,
    /** Present + half-day credit as a percentage of working days. */
    double attendanceRatePct) {

  public record DayMark(int day, String date, String code, String category) {}
}
