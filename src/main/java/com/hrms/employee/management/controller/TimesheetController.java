package com.hrms.employee.management.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.hrms.employee.management.dto.TimesheetDto;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.service.TimesheetService;
import com.hrms.employee.management.utility.JwtUtil;

@RestController
@RequestMapping("/employees/{employeeId}/timesheets")
@CrossOrigin(origins ="*")
public class TimesheetController {

    private final TimesheetService timesheetService;

    public TimesheetController(TimesheetService timesheetService) {
        this.timesheetService = timesheetService;
    }

    @PostMapping
    public ResponseEntity<TimesheetDto> addTimesheet(@PathVariable String employeeId, @RequestBody TimesheetDto timesheetDto) {
        TimesheetDto savedTimesheet = timesheetService.logWork(employeeId, timesheetDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedTimesheet);
    }

    @GetMapping
    public ResponseEntity<List<TimesheetDto>> getTimesheetHistory(@PathVariable String employeeId) {
        List<TimesheetDto> timesheetHistory = timesheetService.getTimesheetByEmployeeId(employeeId);
        return ResponseEntity.ok(timesheetHistory);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TimesheetDto> getTimesheetById(@PathVariable String employeeId, @PathVariable Long id) {
        TimesheetDto timesheet = timesheetService.getTimesheetById(id);

        return ResponseEntity.ok(timesheet);
    }

    @PutMapping("/clock")
    public ResponseEntity<TimesheetDto> clockInOut(@PathVariable String employeeId, @RequestBody TimesheetDto timesheetDto) {
        TimesheetDto result = timesheetService.clock(employeeId, timesheetDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Unified entry point: infers clock punch vs. manual entry from which fields are present.
     * - clockIn + clockOut both present  -> manual entry (overwrite, no state guards)
     * - only clockIn present             -> clock-in punch
     * - only clockOut present            -> clock-out punch (state guards apply)
     */
    @PutMapping("/entry")
    public ResponseEntity<TimesheetDto> recordTimesheetEntry(@PathVariable String employeeId,
                                                             @RequestHeader(value = "Authorization", required = false) String authorization,
                                                             @RequestBody TimesheetDto timesheetDto) {
        String userId = JwtUtil.extractUserId(authorization);
        if (!userId.equals(employeeId)) {
            throw new BusinessException("Timesheet entries can only be recorded for your own employee id.");
        }
        TimesheetDto result = timesheetService.recordTimesheetEntry(employeeId, timesheetDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/date/{date}")
    public ResponseEntity<TimesheetDto> getTimesheetByDate(@PathVariable String employeeId,@PathVariable LocalDate date) {
        TimesheetDto timesheetEntry = timesheetService.getTimesheetByEmployeeIdAndDate(employeeId,date);
        return ResponseEntity.ok(timesheetEntry);
    }
    @GetMapping("/reports/history")
    public ResponseEntity<List<TimesheetDto>> getTimesheetHistory(@PathVariable String employeeId,
                                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate){
        List<TimesheetDto> timesheetHistory = timesheetService.getTimesheetReportByEmployeeId(employeeId,startDate,endDate);
        return ResponseEntity.ok(timesheetHistory);
    }

    @PutMapping("/approve/{id}")
    public ResponseEntity<TimesheetDto> approveTimesheet(@PathVariable String employeeId, @PathVariable Long id) {
        TimesheetDto approvedTimesheet = timesheetService.approveTimesheet(employeeId, id);
        return ResponseEntity.ok(approvedTimesheet);
    }
    @PutMapping("/{id}/status")
    public ResponseEntity<TimesheetDto> updateTimesheetStatus(@PathVariable String employeeId, @PathVariable Long id, @RequestParam String status) {
        TimesheetDto updatedTimesheet = timesheetService.updateTimesheet(employeeId, id,status);
        return ResponseEntity.ok(updatedTimesheet);
    }


}
