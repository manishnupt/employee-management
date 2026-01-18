package com.hrms.employee.management.controller;

import com.hrms.employee.management.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/employee/reports")
public class ReportsController {

    @Autowired
    private ReportService reportService;

    @GetMapping("/by-date/{eployeeId}/date/{date}")
    public String getReportByDate(@PathVariable String employeeId, @PathVariable String date) {
       // return reportService.generateReportByDate(employeeId, date);
        return null;
    }
}
