package com.hrms.employee.management.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class AttendanceReportDto {
    private List<EmployeeReport> employees;

    /** Represents one employee’s attendance report */
    @Data
    public static class EmployeeReport {
        private String employeeId;
        private String employeeName;
        private List<DailyRecord> records;
        private Summary summary;


        /** Represents one day’s attendance record for the employee */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Data
        public static class DailyRecord {
            private String date;        // ISO date string
            private String weekday;
            private String checkIn;     // e.g. "09:00" or null
            private String checkOut;    // e.g. "17:30" or null
            private String totalHours;  // e.g. "8:30"
            private String leave;       // e.g. "Full Day", "Half Day", or null
            private Boolean wfh;
            private String remarks;     // optional notes
            private String overtime;    // e.g. "1:00" or null
        }

        /** Summarizes totals for the employee */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Data
        public static class Summary {
            private Integer totalWorkingDays;
            private Integer totalPresentDays;
            private Double totalLeaveDays;
            private Integer totalWfhDays;
            private String totalHoursWorked;
            private String totalOvertimeHours;
        }
    }
}
