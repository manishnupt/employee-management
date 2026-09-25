package com.hrms.employee.management.service;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dto.AttendanceReportDto;
import com.hrms.employee.management.dto.AttendanceReportDto.DailyRecord;
import com.hrms.employee.management.dto.AttendanceReportDto.DayStatus;
import com.hrms.employee.management.dto.AttendanceReportDto.RequestEntry;
import com.hrms.employee.management.dto.AttendanceReportDto.Summary;
import com.hrms.employee.management.dto.AttendanceReportDto.TimesheetEntry;
import com.hrms.employee.management.dto.TimesheetDto;
import com.hrms.employee.management.dto.WFHTrackerResponse;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.utility.TimesheetUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private static final long MAX_RANGE_DAYS = 366;
    private static final Set<DayOfWeek> WEEKENDS = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    private static final Set<String> INACTIVE_STATUSES = Set.of("REJECTED", "CANCELLED", "CANCELED", "WITHDRAWN");

    @Autowired
    private TimesheetService timesheetService;
    @Autowired
    private LeaveTrackerService leaveTrackerService;
    @Autowired
    private WFHService wfhService;
    @Autowired
    private EmployeeRepository employeeRepository;

    public AttendanceReportDto generateReportByEmployeeAndDateRange(String employeeId, LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("endDate must be on or after startDate");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) >= MAX_RANGE_DAYS) {
            throw new BusinessException("Report range cannot exceed " + MAX_RANGE_DAYS + " days");
        }
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new BusinessException("Employee not found: " + employeeId));

        Map<LocalDate, TimesheetDto> timesheetByDate = timesheetService
                .getTimesheetReportByEmployeeId(employeeId, startDate, endDate).stream()
                .collect(Collectors.toMap(TimesheetDto::getWorkDate, Function.identity(), (a, b) -> a));

        Map<LocalDate, RequestEntry> leaveByDate = expandByDate(
                leaveTrackerService.getLeavesReportByEmployeeId(employeeId, startDate, endDate).stream()
                        .map(ReportService::toRequestEntry).toList(),
                startDate, endDate);

        Map<LocalDate, RequestEntry> wfhByDate = expandByDate(
                wfhService.getWfhReportByEmployeeId(employeeId, startDate, endDate).stream()
                        .map(ReportService::toRequestEntry).toList(),
                startDate, endDate);

        LocalDate today = LocalDate.now(TimesheetUtil.IST);
        List<DailyRecord> days = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            days.add(buildDay(date, today, timesheetByDate.get(date), leaveByDate.get(date), wfhByDate.get(date)));
        }

        return AttendanceReportDto.builder()
                .employeeId(employee.getEmployeeId())
                .employeeName(employee.getName())
                .startDate(startDate)
                .endDate(endDate)
                .summary(summarize(days))
                .days(days)
                .build();
    }

    private DailyRecord buildDay(LocalDate date, LocalDate today, TimesheetDto timesheet,
                                 RequestEntry leave, RequestEntry wfh) {
        boolean weekend = WEEKENDS.contains(date.getDayOfWeek());
        boolean onLeave = leave != null && leave.isApproved();
        boolean onWfh = wfh != null && wfh.isApproved();

        DayStatus status;
        if (onLeave) {
            status = DayStatus.ON_LEAVE;
        } else if (onWfh) {
            status = DayStatus.WFH;
        } else if (timesheet != null) {
            status = DayStatus.PRESENT;
        } else if (weekend) {
            status = DayStatus.WEEKEND;
        } else {
            status = date.isAfter(today) ? DayStatus.UPCOMING : DayStatus.ABSENT;
        }

        List<String> remarks = new ArrayList<>();
        if (onLeave && timesheet != null) {
            remarks.add("Timesheet filled on an approved leave day");
        }
        if (onLeave && onWfh) {
            remarks.add("Approved leave and WFH overlap");
        }
        if (onWfh && !onLeave && timesheet == null && !date.isAfter(today)) {
            remarks.add("WFH approved but no timesheet filled");
        }
        if (timesheet != null && timesheet.getClockOut() == null) {
            remarks.add("Clock-out missing");
        }
        if (leave != null && !leave.isApproved()) {
            remarks.add("Leave request pending approval");
        }
        if (wfh != null && !wfh.isApproved()) {
            remarks.add("WFH request pending approval");
        }

        return DailyRecord.builder()
                .date(date)
                .weekday(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .status(status)
                .timesheet(toTimesheetEntry(timesheet))
                .leave(leave)
                .wfh(wfh)
                .remarks(remarks.isEmpty() ? null : remarks)
                .build();
    }

    private Summary summarize(List<DailyRecord> days) {
        Map<DayStatus, Integer> counts = new EnumMap<>(DayStatus.class);
        long workedMinutes = 0;
        int daysWithHours = 0;
        int pendingRequestDays = 0;

        for (DailyRecord day : days) {
            counts.merge(day.getStatus(), 1, Integer::sum);
            Long minutes = day.getTimesheet() != null ? day.getTimesheet().getWorkedMinutes() : null;
            if (minutes != null) {
                workedMinutes += minutes;
                daysWithHours++;
            }
            boolean workingDay = !WEEKENDS.contains(day.getDate().getDayOfWeek());
            boolean pending = (day.getLeave() != null && !day.getLeave().isApproved())
                    || (day.getWfh() != null && !day.getWfh().isApproved());
            if (workingDay && pending) {
                pendingRequestDays++;
            }
        }

        int weekendDays = (int) days.stream().filter(d -> WEEKENDS.contains(d.getDate().getDayOfWeek())).count();
        int office = counts.getOrDefault(DayStatus.PRESENT, 0);
        int wfh = counts.getOrDefault(DayStatus.WFH, 0);

        return Summary.builder()
                .totalDays(days.size())
                .workingDays(days.size() - weekendDays)
                .weekendDays(weekendDays)
                .presentDays(office + wfh)
                .officeDays(office)
                .wfhDays(wfh)
                .leaveDays(counts.getOrDefault(DayStatus.ON_LEAVE, 0))
                .absentDays(counts.getOrDefault(DayStatus.ABSENT, 0))
                .upcomingDays(counts.getOrDefault(DayStatus.UPCOMING, 0))
                .pendingRequestDays(pendingRequestDays)
                .totalWorkedMinutes(workedMinutes)
                .totalWorkedHours(TimesheetUtil.formatMinutesInWords(workedMinutes))
                .averageWorkedHoursPerDay(daysWithHours == 0 ? null
                        : TimesheetUtil.formatMinutesInWords(workedMinutes / daysWithHours))
                .build();
    }

    /**
     * Maps each date in [from, to] to the request covering it. Rejected/cancelled requests are
     * dropped; when several requests cover the same day, an approved one wins over a pending one.
     */
    private static Map<LocalDate, RequestEntry> expandByDate(List<RequestEntry> requests, LocalDate from, LocalDate to) {
        Map<LocalDate, RequestEntry> byDate = new HashMap<>();
        requests.stream()
                .filter(r -> r.getStartDate() != null && r.getEndDate() != null)
                .filter(r -> r.getStatus() == null || !INACTIVE_STATUSES.contains(r.getStatus().toUpperCase(Locale.ROOT)))
                .sorted(Comparator.comparing(RequestEntry::isApproved))
                .forEach(r -> {
                    LocalDate start = r.getStartDate().isBefore(from) ? from : r.getStartDate();
                    LocalDate end = r.getEndDate().isAfter(to) ? to : r.getEndDate();
                    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                        byDate.put(d, r);
                    }
                });
        return byDate;
    }

    private static TimesheetEntry toTimesheetEntry(TimesheetDto ts) {
        if (ts == null) {
            return null;
        }
        Long minutes = null;
        if (ts.getClockIn() != null && ts.getClockOut() != null) {
            minutes = TimesheetUtil.calculateTotalMinutes(ts.getWorkDate(), ts.getWorkDate(), ts.getClockIn(), ts.getClockOut());
        }
        return TimesheetEntry.builder()
                .timesheetId(ts.getTimesheetId())
                .clockIn(ts.getClockIn())
                .clockOut(ts.getClockOut())
                .workedMinutes(minutes)
                .workedHours(minutes != null ? TimesheetUtil.formatMinutesInWords(minutes) : null)
                .status(ts.getStatus())
                .build();
    }

    private static RequestEntry toRequestEntry(LeaveTracker leave) {
        return RequestEntry.builder()
                .id(leave.getId())
                .type(leave.getLeaveType())
                .status(leave.getStatus())
                .approved(isApproved(leave.getStatus()))
                .reason(leave.getReason())
                .startDate(leave.getStartDate())
                .endDate(leave.getEndDate())
                .build();
    }

    private static RequestEntry toRequestEntry(WFHTrackerResponse wfh) {
        return RequestEntry.builder()
                .id(wfh.getId())
                .status(wfh.getStatus())
                .approved(isApproved(wfh.getStatus()))
                .reason(wfh.getReason())
                .startDate(wfh.getStartDate())
                .endDate(wfh.getEndDate())
                .build();
    }

    private static boolean isApproved(String status) {
        return "APPROVED".equalsIgnoreCase(status);
    }
}
