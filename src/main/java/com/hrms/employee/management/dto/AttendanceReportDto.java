package com.hrms.employee.management.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Day-by-day attendance report for one employee over a date range. Every calendar day in the
 * range gets exactly one {@link DailyRecord}, carrying the timesheet, leave, WFH and regularization
 * entries (if any) that apply to that day plus a resolved {@link DayStatus}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Builder
public class AttendanceReportDto {

    private String employeeId;
    private String employeeName;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDate;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDate;
    private Summary summary;
    private List<DailyRecord> days;

    public enum DayStatus {
        /** Timesheet filled from office, including one produced by an approved regularization. */
        PRESENT,
        /** Approved WFH; worked hours come from the timesheet if one was filled. */
        WFH,
        /** Approved leave. */
        ON_LEAVE,
        WEEKEND,
        /**
         * Working day in the past with no timesheet, approved leave or approved WFH. A regularization
         * that is still pending does not change this; the day turns PRESENT once it is approved.
         */
        ABSENT,
        /** Working day after today with nothing recorded yet. */
        UPCOMING
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    @Builder
    public static class DailyRecord {
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate date;
        private String weekday;
        private DayStatus status;
        private TimesheetEntry timesheet;
        private RequestEntry leave;
        private RequestEntry wfh;
        /** Pending or approved regularization raised for the day; rejected ones are left out. */
        private RegularizationEntry regularization;
        /** Inconsistencies worth a reviewer's attention, e.g. a timesheet filled on a leave day. */
        private List<String> remarks;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    @Builder
    public static class TimesheetEntry {
        private Long timesheetId;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
        private LocalTime clockIn;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
        private LocalTime clockOut;
        /** Null while the employee has not clocked out. */
        private Long workedMinutes;
        private String workedHours;
        private String status;
        /** True when the times came from an approved regularization rather than the employee's punches. */
        private boolean regularised;
    }

    /** A regularization request for the day, with the times the employee asked to be recorded. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    @Builder
    public static class RegularizationEntry {
        private Long id;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
        private LocalTime clockIn;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm")
        private LocalTime clockOut;
        private Long requestedMinutes;
        private String requestedHours;
        private String status;
        private boolean approved;
        private String reason;
    }

    /** A leave or WFH request covering the day. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    @Builder
    public static class RequestEntry {
        private Long id;
        /** Leave type name; null for WFH. */
        private String type;
        private String status;
        private boolean approved;
        private String reason;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate startDate;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate endDate;
    }

    @Data
    @Builder
    public static class Summary {
        private int totalDays;
        private int workingDays;
        private int weekendDays;
        /** Office days plus WFH days. */
        private int presentDays;
        private int officeDays;
        private int wfhDays;
        private int leaveDays;
        private int absentDays;
        private int upcomingDays;
        /** Days whose timesheet was produced by an approved regularization. */
        private int regularizedDays;
        /**
         * Days with a request still awaiting approval: working days covered by a pending leave/WFH,
         * plus any day with a pending regularization.
         */
        private int pendingRequestDays;
        /** Days with a regularization still awaiting approval; these are also part of pendingRequestDays. */
        private int pendingRegularizationDays;
        private long totalWorkedMinutes;
        private String totalWorkedHours;
        private String averageWorkedHoursPerDay;
    }
}
