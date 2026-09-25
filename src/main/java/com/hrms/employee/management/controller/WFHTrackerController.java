package com.hrms.employee.management.controller;

import java.time.LocalDate;
import java.util.List;

import com.hrms.employee.management.dto.TimesheetDto;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.utility.JwtUtil;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.hrms.employee.management.dto.WFHTrackerRequest;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.service.WFHService;
import com.hrms.employee.management.dto.WFHTrackerResponse;


@RestController
@RequestMapping("/employees/{employeeId}/wfh")
@CrossOrigin(origins ="*")
public class WFHTrackerController {
    
    private final WFHService wfhService;

    public WFHTrackerController(WFHService wfhService) {
        this.wfhService = wfhService;
    }

    @PostMapping
    public ResponseEntity<WFHTracker> applyWFH(@PathVariable String employeeId, @RequestBody WFHTrackerRequest wfmTrackerDto, @RequestHeader(value = "Authorization") String authorization){

        String userId = JwtUtil.extractUserId(authorization);
        if (!userId.equals(employeeId)) {
            throw new BusinessException("Timesheet entries can only be recorded for your own employee id.");
        }
        WFHTracker wfhTracker = wfhService.applyWFH(employeeId, wfmTrackerDto);
        return ResponseEntity.ok(wfhTracker);
    }
    @GetMapping("/{id}")
    public ResponseEntity<WFHTracker> getWFHDetailsById(@PathVariable String employeeId, @PathVariable Long id) {
        WFHTracker wfhTracker = wfhService.getWFHDetailsById(employeeId, id);
        return ResponseEntity.ok(wfhTracker);
    }

    @GetMapping
    public ResponseEntity<List<WFHTracker>> getWFHHistory(@PathVariable String employeeId) {
        List<WFHTracker> wfhTrackers = wfhService.getWFHHistory(employeeId);
        return ResponseEntity.ok(wfhTrackers);
    }
    @GetMapping("/date")
    public ResponseEntity<WFHTracker> getWFHByDate(@PathVariable String employeeId, @RequestParam LocalDate date) {
        WFHTracker wfhTracker = wfhService.getWFHByDate(employeeId,date);
        return ResponseEntity.ok(wfhTracker);
    }

    @GetMapping("/reports/history")
    public ResponseEntity<List<WFHTrackerResponse>> getTimesheetHistory(@PathVariable String employeeId,
                                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate){
        List<WFHTrackerResponse> wfhResponse = wfhService.getWfhReportByEmployeeId(employeeId,startDate,endDate);
        return ResponseEntity.ok(wfhResponse);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<WFHTracker> updateWFHStatus(@PathVariable String employeeId, @PathVariable Long id, @RequestParam String status) {
        WFHTracker updatedWFH = wfhService.updateWFHStatus(employeeId, id, status);
        return ResponseEntity.ok(updatedWFH);
    }
}
