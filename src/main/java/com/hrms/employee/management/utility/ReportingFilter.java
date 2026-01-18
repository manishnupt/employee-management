package com.hrms.employee.management.utility;

import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dto.AttendanceReportDto;
import com.hrms.employee.management.dto.EmployeeReportResponse;
import com.hrms.employee.management.dto.TimesheetDto;
import com.hrms.employee.management.dto.WFHTrackerResponse;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class ReportingFilter {
    public static AttendanceReportDto convertToTimesheetResponse(
            List<TimesheetDto> timesheetReports,
            List<LeaveTracker> leaveReports,
            List<WFHTrackerResponse> wfhReports) {

        AttendanceReportDto reportDto = new AttendanceReportDto();
        List<AttendanceReportDto.EmployeeReport> employeeReports = new ArrayList<>();
        reportDto.setEmployees(employeeReports);

        Map<String, List<TimesheetDto>> timesheetsByEmp = timesheetReports.stream()
                .collect(Collectors.groupingBy(TimesheetDto::getEmployeeId));

        Map<String, List<LeaveTracker>> leaveByEmp = leaveReports.stream()
                .collect(Collectors.groupingBy(l -> l.getEmployee().getEmployeeId()));

        Map<String, List<WFHTrackerResponse>> wfhByEmp = wfhReports.stream()
                .collect(Collectors.groupingBy(w -> w.getEmployee().getId().toString()));

        Set<String> allEmpIds = new HashSet<>();
        allEmpIds.addAll(timesheetsByEmp.keySet());
        allEmpIds.addAll(leaveByEmp.keySet());
        allEmpIds.addAll(wfhByEmp.keySet());

        for (String empId : allEmpIds) {
            AttendanceReportDto.EmployeeReport empReport = new AttendanceReportDto.EmployeeReport();
            empReport.setEmployeeId(empId);

            if (leaveByEmp.containsKey(empId) && !leaveByEmp.get(empId).isEmpty()) {
                empReport.setEmployeeName(leaveByEmp.get(empId).get(0).getEmployee().getName());
            } else if (wfhByEmp.containsKey(empId) && !wfhByEmp.get(empId).isEmpty()) {
                empReport.setEmployeeName(wfhByEmp.get(empId).get(0).getEmployee().getName());
            }

            Map<LocalDate, AttendanceReportDto.EmployeeReport.DailyRecord> recordsByDate = new TreeMap<>();

            for (TimesheetDto ts : timesheetsByEmp.getOrDefault(empId, Collections.emptyList())) {
                LocalDate date = ts.getWorkDate();
                AttendanceReportDto.EmployeeReport.DailyRecord rec = new AttendanceReportDto.EmployeeReport.DailyRecord();
                rec.setDate(date.toString());
                rec.setCheckIn(ts.getClockIn() != null ? ts.getClockIn().toString() : null);
                rec.setCheckOut(ts.getClockOut() != null ? ts.getClockOut().toString() : null);
                rec.setTotalHours(String.format("%02d:%02d", (int) ts.getTotalHours(), (int)((ts.getTotalHours() % 1) * 60)));
                recordsByDate.put(date, rec);
            }

            for (LeaveTracker leave : leaveByEmp.getOrDefault(empId, Collections.emptyList())) {
                LocalDate start = leave.getStartDate();
                LocalDate end = leave.getEndDate();
                for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                    AttendanceReportDto.EmployeeReport.DailyRecord rec = recordsByDate.getOrDefault(d, new AttendanceReportDto.EmployeeReport.DailyRecord());
                    rec.setDate(d.toString());
                    if ("Half Day".equalsIgnoreCase(leave.getLeaveType())) {
                        rec.setLeave("Half Day");
                    } else {
                        rec.setLeave("Full Day");
                    }
                    recordsByDate.put(d, rec);
                }
            }

            for (WFHTrackerResponse wfh : wfhByEmp.getOrDefault(empId, Collections.emptyList())) {
                LocalDate start = wfh.getStartDate();
                LocalDate end = wfh.getEndDate();
                for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                    AttendanceReportDto.EmployeeReport.DailyRecord rec = recordsByDate.getOrDefault(d, new AttendanceReportDto.EmployeeReport.DailyRecord());
                    rec.setDate(d.toString());
                    rec.setWfh(true);
                    recordsByDate.put(d, rec);
                }
            }

            List<AttendanceReportDto.EmployeeReport.DailyRecord> dailyRecords = new ArrayList<>(recordsByDate.values());
            empReport.setRecords(dailyRecords);

            AttendanceReportDto.EmployeeReport.Summary summary = new AttendanceReportDto.EmployeeReport.Summary();
            int totalWorkingDays = dailyRecords.size();
            int presentDays = 0;
            double leaveDays = 0;
            int wfhDays = 0;
            Duration totalDuration = Duration.ZERO;

            for (AttendanceReportDto.EmployeeReport.DailyRecord rec : dailyRecords) {
                String hours = rec.getTotalHours();
                boolean wfhDay = Boolean.TRUE.equals(rec.getWfh());
                boolean hasHours = hours != null && !"00:00".equals(hours);

                if (wfhDay || hasHours) presentDays++;
                if (wfhDay) wfhDays++;
                if ("Full Day".equals(rec.getLeave())) leaveDays += 1;
                else if ("Half Day".equals(rec.getLeave())) leaveDays += 0.5;

                if (hasHours) {
                    String[] parts = hours.split(":");
                    totalDuration = totalDuration.plusMinutes(Integer.parseInt(parts[0]) * 60L + Integer.parseInt(parts[1]));
                }
            }

            summary.setTotalWorkingDays(totalWorkingDays);
            summary.setTotalPresentDays(presentDays);
            summary.setTotalLeaveDays(leaveDays);
            summary.setTotalWfhDays(wfhDays);
            summary.setTotalHoursWorked(String.format("%d:%02d", totalDuration.toHours(), totalDuration.toMinutes() % 60));
            summary.setTotalOvertimeHours(null);

            empReport.setSummary(summary);
            employeeReports.add(empReport);
        }

        return reportDto;
    }

}
