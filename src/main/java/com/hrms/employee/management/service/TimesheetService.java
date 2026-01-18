package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.util.List;

import com.hrms.employee.management.dto.TimesheetDto;

public interface TimesheetService {
	TimesheetDto logWork(String employeeId, TimesheetDto timesheetDto);
    List<TimesheetDto> getTimesheetByEmployeeId(String employeeId);
    TimesheetDto getTimesheetById(Long id);
    TimesheetDto clock(String employeeId, TimesheetDto timesheetDto);

    TimesheetDto getTimesheetByEmployeeIdAndDate(String employeeId, LocalDate date);

    List<TimesheetDto> getTimesheetReportByEmployeeId(String employeeId, LocalDate startDate, LocalDate endDate);
}

