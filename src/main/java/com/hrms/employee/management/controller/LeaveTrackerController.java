package com.hrms.employee.management.controller;

import java.time.LocalDate;
import java.util.List;

import com.hrms.employee.management.service.LeaveTrackerService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dto.LeaveTrackerDto;
import com.hrms.employee.management.dto.LeaveTrackerResponse;

@RestController
@RequestMapping("/employees/{employeeId}/leave-tracker")
@CrossOrigin(origins ="*")
public class LeaveTrackerController {

    private final LeaveTrackerService leaveTrackerService;

    public LeaveTrackerController(LeaveTrackerService leaveTrackerService) {
        this.leaveTrackerService = leaveTrackerService;
    }

    @PostMapping
    public ResponseEntity<LeaveTrackerResponse> applyLeave(@PathVariable String employeeId, @RequestBody LeaveTrackerDto leaveTrackerDto) {
        LeaveTrackerResponse leave = leaveTrackerService.applyLeave(employeeId, leaveTrackerDto);
        return ResponseEntity.ok(leave);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LeaveTracker> getLeaveById(@PathVariable String employeeId, @PathVariable Long id) {
        LeaveTracker leave = leaveTrackerService.getLeaveById(id);
        return ResponseEntity.ok(leave);
    }

    @GetMapping
    public ResponseEntity<List<LeaveTracker>> getLeaveHistory(@PathVariable String employeeId) {
        List<LeaveTracker> leaveHistory = leaveTrackerService.getLeaveHistory(employeeId);
        return ResponseEntity.ok(leaveHistory);
    }

    @GetMapping("/reports/history")
    public ResponseEntity<List<LeaveTracker>> getAllLeaves(@PathVariable String employeeId,
                                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate){
        List<LeaveTracker> leaves = leaveTrackerService.getLeavesReportByEmployeeId(employeeId,startDate,endDate);
        return ResponseEntity.ok(leaves);
    }
}