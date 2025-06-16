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


@RestController
@RequestMapping("/employee/leave-balance")
@CrossOrigin(origins = "*")
@Validated
public class LeaveBalanceController {

    @Autowired
    private LeaveBalanceService leaveBalanceService;

    @Autowired
    private LeaveDisbursalSchedulerService leaveDisbursalSchedulerService;

    @GetMapping("/{employeeId}")
    public ResponseEntity<List<LeaveBalanceDto>> getEmployeeLeaveBalances(@PathVariable String employeeId) {
        return ResponseEntity.ok(leaveBalanceService.getEmployeeLeaveBalances(employeeId));
    }

    // @GetMapping("/{employeeId}/{leaveTypeId}")
    // public ResponseEntity<LeaveBalanceDto> getEmployeeLeaveBalance(@PathVariable String employeeId,
    //                                                                @PathVariable String leaveTypeId) {
    //     return ResponseEntity.ok(leaveBalanceService.getEmployeeLeaveBalance(employeeId, leaveTypeId));
    // }

    @PostMapping("/initialize/{employeeId}")
    public ResponseEntity<String> initializeLeaveBalanceForNewEmployee(@PathVariable String employeeId) {
        leaveBalanceService.initializeLeaveBalanceForNewEmployee(employeeId);
        return ResponseEntity.ok("Leave balances initialized successfully");
    }

    @PostMapping("/initialize-for-new-leave-type")
    public ResponseEntity<String> initializeLeaveBalanceForNewLeaveType(@RequestBody LeaveBalanceService.LeaveType leaveType) {
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

    // @PostMapping("/deduct/{employeeId}")
    // public ResponseEntity<String> deductLeaveFromEmployee(@PathVariable String employeeId,
    //                                                       @Valid @RequestBody LeaveDeductionDto deductionDto) {
    //     leaveBalanceService.deductLeaveFromEmployee(employeeId, deductionDto);
    //     return ResponseEntity.ok("Leave deducted successfully");
    // }

    // @PutMapping("/deactivate-leave-type/{leaveTypeId}")
    // public ResponseEntity<String> deactivateLeaveType(@PathVariable String leaveTypeId) {
    //     leaveBalanceService.deactivateLeaveType(leaveTypeId);
    //     return ResponseEntity.ok("Leave type deactivated successfully");
    // }

    @PostMapping("/disburse-leave")
    public ResponseEntity<String> disburseLeave() {
        leaveDisbursalSchedulerService.disburseMonthlyLeave();
        return ResponseEntity.ok("Leave disbursed successfully");
    }
    
}