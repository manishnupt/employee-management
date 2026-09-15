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

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("/employees/{employeeId}/leave-tracker")
@CrossOrigin(origins ="*")
@Log4j2
public class LeaveTrackerController {

    private final LeaveTrackerService leaveTrackerService;

    public LeaveTrackerController(LeaveTrackerService leaveTrackerService) {
        this.leaveTrackerService = leaveTrackerService;
    }

    @PostMapping
    public ResponseEntity<LeaveTrackerResponse> applyLeave(@PathVariable String employeeId, @RequestBody LeaveTrackerDto leaveTrackerDto) {
        log.info("applyLeave called for employeeId={} leaveType={}", employeeId, leaveTrackerDto.getLeaveType());
        LeaveTrackerResponse leave = leaveTrackerService.applyLeave(employeeId, leaveTrackerDto);
        return ResponseEntity.ok(leave);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LeaveTracker> getLeaveById(@PathVariable String employeeId, @PathVariable Long id) {
        log.info("getLeaveById called for employeeId={} id={}", employeeId, id);
        LeaveTracker leave = leaveTrackerService.getLeaveById(id);
        return ResponseEntity.ok(leave);
    }

    @GetMapping
    public ResponseEntity<List<LeaveTracker>> getLeaveHistory(@PathVariable String employeeId) {
        log.info("getLeaveHistory called for employeeId={}", employeeId);
        List<LeaveTracker> leaveHistory = leaveTrackerService.getLeaveHistory(employeeId);
        return ResponseEntity.ok(leaveHistory);
    }

    @GetMapping("/reports/history")
    public ResponseEntity<List<LeaveTracker>> getAllLeaves(@PathVariable String employeeId,
                                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate){
        log.info("getAllLeaves called for employeeId={} startDate={} endDate={}", employeeId, startDate, endDate);
        List<LeaveTracker> leaves = leaveTrackerService.getLeavesReportByEmployeeId(employeeId,startDate,endDate);
        return ResponseEntity.ok(leaves);
    }
}