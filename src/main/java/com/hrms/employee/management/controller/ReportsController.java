package com.hrms.employee.management.controller;

import com.hrms.employee.management.dto.AttendanceReportDto;
import com.hrms.employee.management.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/employee/reports")
public class ReportsController {

    @Autowired
    private ReportService reportService;

    @GetMapping("/by-date/{employeeId}/date")
    public AttendanceReportDto getReportByDate(@PathVariable String employeeId,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate){
        return reportService.generateReportByEmployeeAndDateRange(employeeId, startDate, endDate);

    }
}
