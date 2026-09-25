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
 * range gets exactly one {@link DailyRecord}, carrying the timesheet, leave and WFH entries
 * (if any) that apply to that day plus a resolved {@link DayStatus}.
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
        /** Timesheet filled from office. */
        PRESENT,
        /** Approved WFH; worked hours come from the timesheet if one was filled. */
        WFH,
        /** Approved leave. */
        ON_LEAVE,
        WEEKEND,
        /** Working day in the past with no timesheet, approved leave or approved WFH. */
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
        /** Leave/WFH requests in the range still awaiting approval, counted in working days. */
        private int pendingRequestDays;
        private long totalWorkedMinutes;
        private String totalWorkedHours;
        private String averageWorkedHoursPerDay;
    }
}
