package com.hrms.employee.management.controller;

import com.hrms.employee.management.dto.LeaveAssignmentDto;
import com.hrms.employee.management.dto.LeaveBalanceDto;
import com.hrms.employee.management.dto.BulkLeaveAssignmentDto;
import com.hrms.employee.management.dto.LeaveDeductionDto;
import com.hrms.employee.management.service.LeaveBalanceService;
import com.hrms.employee.management.service.LeaveDisbursalSchedulerService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import java.util.List;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("/employee/leave-balance")
@CrossOrigin(origins = "*")
@Validated
@Log4j2
public class LeaveBalanceController {

    @Autowired
    private LeaveBalanceService leaveBalanceService;

    @Autowired
    private LeaveDisbursalSchedulerService leaveDisbursalSchedulerService;

    @GetMapping("/{employeeId}")
    public ResponseEntity<List<LeaveBalanceDto>> getEmployeeLeaveBalances(@PathVariable String employeeId) {
        log.info("getEmployeeLeaveBalances called for employeeId={}", employeeId);
        return ResponseEntity.ok(leaveBalanceService.getEmployeeLeaveBalances(employeeId));
    }

    // @GetMapping("/{employeeId}/{leaveTypeId}")
    // public ResponseEntity<LeaveBalanceDto> getEmployeeLeaveBalance(@PathVariable String employeeId,
    //                                                                @PathVariable String leaveTypeId) {
    //     return ResponseEntity.ok(leaveBalanceService.getEmployeeLeaveBalance(employeeId, leaveTypeId));
    // }

    @PostMapping("/initialize/{employeeId}")
    public ResponseEntity<String> initializeLeaveBalanceForNewEmployee(@PathVariable String employeeId) {
        log.info("initializeLeaveBalanceForNewEmployee called for employeeId={}", employeeId);
        leaveBalanceService.initializeLeaveBalanceForNewEmployee(employeeId);
        return ResponseEntity.ok("Leave balances initialized successfully");
    }

    @PostMapping("/initialize-for-new-leave-type")
    public ResponseEntity<String> initializeLeaveBalanceForNewLeaveType(@RequestBody LeaveBalanceService.LeaveType leaveType) {
        log.info("initializeLeaveBalanceForNewLeaveType called for leaveType={}", leaveType.getName());
        leaveBalanceService.initializeLeaveBalanceForNewLeaveType(leaveType);
        return ResponseEntity.ok("Leave balances initialized for new leave type");
    }

    // @PostMapping("/assign/{employeeId}")
    // public ResponseEntity<String> assignLeaveToEmployee(@PathVariable String employeeId,
    //                                                     @Valid @RequestBody LeaveAssignmentDto assignmentDto) {
    //     leaveBalanceService.assignLeaveToEmployee(employeeId, assignmentDto.getLeaveTypeId(),
    //             assignmentDto.getDays(), assignmentDto.getReason());
    //     return ResponseEntity.ok("Leave assigned successfully");
    // }

    // @PostMapping("/bulk-assign")
    // public ResponseEntity<String> bulkAssignLeave(@Valid @RequestBody BulkLeaveAssignmentDto assignmentDto) {
    //     leaveBalanceService.bulkAssignLeave(assignmentDto);
    //     return ResponseEntity.ok("Leave assigned to all employees successfully");
    // }

    @PostMapping("/{employeeId}/deduct/{leaveId}")
    public ResponseEntity<String> deductLeaveFromEmployee(@PathVariable String employeeId,
                                                          @PathVariable Long leaveId) {
        log.info("deductLeaveFromEmployee called for employeeId={} leaveId={}", employeeId, leaveId);
        leaveBalanceService.deductLeaveFromEmployee(employeeId, leaveId);
        return ResponseEntity.ok("Leave deducted successfully");
    }

    // @PutMapping("/deactivate-leave-type/{leaveTypeId}")
    // public ResponseEntity<String> deactivateLeaveType(@PathVariable String leaveTypeId) {
    //     leaveBalanceService.deactivateLeaveType(leaveTypeId);
    //     return ResponseEntity.ok("Leave type deactivated successfully");
    // }

    @PostMapping("/disburse-monthly-leave")
    public ResponseEntity<String> disburseLeave() {
        log.info("disburseLeave (monthly) called");
        leaveDisbursalSchedulerService.disburseMonthlyLeave();
        return ResponseEntity.ok("Leave disbursed successfully");
    }
    @PostMapping("/disburse-yearly-leave")
    public ResponseEntity<String> disburseYearlyLeave() {
        log.info("disburseYearlyLeave called");
        leaveDisbursalSchedulerService.disburseYearlyLeave();
        return ResponseEntity.ok("Yearly leave disbursed successfully");
    }
    @PostMapping("/disburse-quarterly-leave")
    public ResponseEntity<String> disburseQuarterlyLeave() {
        log.info("disburseQuarterlyLeave called");
        leaveDisbursalSchedulerService.disburseQuarterlyLeave();
        return ResponseEntity.ok("Quarterly leave disbursed successfully");
    }
    @PostMapping("/disburse-half-yearly-leave")
    public ResponseEntity<String> disburseHalfYearlyLeave() {
        log.info("disburseHalfYearlyLeave called");
        leaveDisbursalSchedulerService.disburseHalfYearlyLeave();
        return ResponseEntity.ok("Half-yearly leave disbursed successfully");
    }
    
}