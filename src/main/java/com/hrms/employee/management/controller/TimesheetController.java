package com.hrms.employee.management.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.hrms.employee.management.dto.TimesheetDto;
import com.hrms.employee.management.service.TimesheetService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("/employees/{employeeId}/timesheets")
@CrossOrigin(origins ="*")
@Log4j2
public class TimesheetController {

    private final TimesheetService timesheetService;

    public TimesheetController(TimesheetService timesheetService) {
        this.timesheetService = timesheetService;
    }

    @PostMapping
    public ResponseEntity<TimesheetDto> addTimesheet(@PathVariable String employeeId, @RequestBody TimesheetDto timesheetDto) {
        log.info("addTimesheet called for employeeId={} workDate={}", employeeId, timesheetDto.getWorkDate());
        TimesheetDto savedTimesheet = timesheetService.logWork(employeeId, timesheetDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedTimesheet);
    }

    @GetMapping
    public ResponseEntity<List<TimesheetDto>> getTimesheetHistory(@PathVariable String employeeId) {
        log.info("getTimesheetHistory called for employeeId={}", employeeId);
        List<TimesheetDto> timesheetHistory = timesheetService.getTimesheetByEmployeeId(employeeId);
        return ResponseEntity.ok(timesheetHistory);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TimesheetDto> getTimesheetById(@PathVariable String employeeId, @PathVariable Long id) {
        log.info("getTimesheetById called for employeeId={} id={}", employeeId, id);
        TimesheetDto timesheet = timesheetService.getTimesheetById(id);

        return ResponseEntity.ok(timesheet);
    }

    @PutMapping("/clock")
    public ResponseEntity<TimesheetDto> clockInOut(@PathVariable String employeeId, @RequestBody TimesheetDto timesheetDto) {
        log.info("clockInOut called for employeeId={}", employeeId);
        TimesheetDto result = timesheetService.clock(employeeId, timesheetDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/date/{date}")
    public ResponseEntity<TimesheetDto> getTimesheetByDate(@PathVariable String employeeId,@PathVariable LocalDate date) {
        log.info("getTimesheetByDate called for employeeId={} date={}", employeeId, date);
        TimesheetDto timesheetEntry = timesheetService.getTimesheetByEmployeeIdAndDate(employeeId,date);
        return ResponseEntity.ok(timesheetEntry);
    }
    @GetMapping("/reports/history")
    public ResponseEntity<List<TimesheetDto>> getTimesheetHistory(@PathVariable String employeeId,
                                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate){
        log.info("getTimesheetHistory (report) called for employeeId={} startDate={} endDate={}", employeeId, startDate, endDate);
        List<TimesheetDto> timesheetHistory = timesheetService.getTimesheetReportByEmployeeId(employeeId,startDate,endDate);
        return ResponseEntity.ok(timesheetHistory);
    }

}
