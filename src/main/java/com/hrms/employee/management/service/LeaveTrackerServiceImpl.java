package com.hrms.employee.management.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dto.LeaveTrackerDto;
import com.hrms.employee.management.dto.LeaveTrackerResponse;
import com.hrms.employee.management.repository.EmployeeLeaveBalanceRepository;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTrackerRepository;

@Service
public class LeaveTrackerServiceImpl implements LeaveTrackerService {


    private final LeaveTrackerRepository leaveTrackerRepository;
    private final EmployeeRepository employeeRepository;
    private final ActionItemService actionItemService;
    private final EmployeeLeaveBalanceRepository employeeLeaveBalanceRepository;

    public LeaveTrackerServiceImpl(LeaveTrackerRepository leaveTrackerRepository, EmployeeRepository employeeRepository,ActionItemService actionItemService
            , EmployeeLeaveBalanceRepository employeeLeaveBalanceRepository) {
        this.leaveTrackerRepository = leaveTrackerRepository;
        this.employeeRepository = employeeRepository;
        this.actionItemService=actionItemService;
        this.employeeLeaveBalanceRepository = employeeLeaveBalanceRepository;
    }

    @Override
    public LeaveTrackerResponse applyLeave(String employeeId, LeaveTrackerDto leaveTrackerDto) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        List<EmployeeLeaveBalance> employeeLeaveBalance =employeeLeaveBalanceRepository.findByEmployeeIdAndIsActiveTrue(employeeId);
        if (employeeLeaveBalance.isEmpty()) {
            return new LeaveTrackerResponse("No active leave balance found for employee", "Failed");
        }
        Optional<EmployeeLeaveBalance> leaveBalance = employeeLeaveBalance.stream()
                .filter(balance -> balance.getLeaveTypeName().equals(leaveTrackerDto.getLeaveType()))
                .findFirst();

        if (!leaveBalance.isPresent()) {
            return new LeaveTrackerResponse("Leave type not found in employee's leave balance", "Failed");

        }
        int days = leaveTrackerDto.getEndDate().getDayOfYear() - leaveTrackerDto.getStartDate().getDayOfYear() + 1;
        if (days > leaveBalance.get().getLeaveBalance()) {
            return new LeaveTrackerResponse("Insufficient leave balance for the requested leave type", "Failed");
        }
        LeaveTracker leaveTracker = new LeaveTracker();
        leaveTracker.setEmployee(employee);
        leaveTracker.setStartDate(leaveTrackerDto.getStartDate());
        leaveTracker.setEndDate(leaveTrackerDto.getEndDate());
        leaveTracker.setLeaveType(leaveTrackerDto.getLeaveType());
        leaveTracker.setStatus("Pending");
        leaveTracker.setReason(leaveTracker.getReason());

        LeaveTracker savedLeave = leaveTrackerRepository.save(leaveTracker);

        actionItemService.createActionItem(employeeId,savedLeave,employee.getAssignedManagerId());
        return new LeaveTrackerResponse("Leave applied successfully", "Success");

    }
    @Override
    public LeaveTracker getLeaveById(Long id) {
        return leaveTrackerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Leave not found"));
    }

    @Override
    public List<LeaveTracker> getLeaveHistory(String employeeId) {
        return leaveTrackerRepository.findByEmployee_EmployeeId(employeeId);
    }
}