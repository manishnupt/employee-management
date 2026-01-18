package com.hrms.employee.management.service;

import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dto.AttendanceReportDto;
import com.hrms.employee.management.dto.TimesheetDto;
import com.hrms.employee.management.dto.WFHTrackerResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import com.hrms.employee.management.utility.ReportingFilter;

@Service
public class ReportService {
    @Autowired
    private TimesheetService timesheetService;
    @Autowired
    private LeaveTrackerService leaveTrackerService;
    @Autowired
    private WFHService wfhService;
    public AttendanceReportDto generateReportByEmployeeAndDateRange(String employeeId, LocalDate startDate, LocalDate endDate) {
        List<TimesheetDto> timesheetReports= timesheetService.getTimesheetReportByEmployeeId(employeeId, startDate, endDate);
        List<LeaveTracker> leaveReports = leaveTrackerService.getLeavesReportByEmployeeId(employeeId, startDate, endDate);
        List<WFHTrackerResponse> wfhReports = wfhService.getWfhReportByEmployeeId(employeeId, startDate, endDate);
        return ReportingFilter.convertToTimesheetResponse(timesheetReports, leaveReports, wfhReports);
    }

}
