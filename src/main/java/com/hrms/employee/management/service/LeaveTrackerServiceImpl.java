package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.hrms.employee.management.exceptions.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dto.LeaveTrackerDto;
import com.hrms.employee.management.dto.LeaveTrackerResponse;
import com.hrms.employee.management.repository.EmployeeLeaveBalanceRepository;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTrackerRepository;

@Slf4j
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
        log.info("Applying leave for employeeId: {}, leaveType: {}, startDate: {}, endDate: {}", employeeId, leaveTrackerDto.getLeaveType(), leaveTrackerDto.getStartDate(), leaveTrackerDto.getEndDate());
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        List<EmployeeLeaveBalance> employeeLeaveBalance =employeeLeaveBalanceRepository.findByEmployeeIdAndIsActiveTrue(employeeId);
        if (employeeLeaveBalance.isEmpty()) {
            throw new BusinessException("No active leave balance found for employee");
        }
        Optional<EmployeeLeaveBalance> leaveBalance = employeeLeaveBalance.stream()
                .filter(balance -> balance.getLeaveTypeName().equals(leaveTrackerDto.getLeaveType()))
                .findFirst();

        if (!leaveBalance.isPresent()) {
            throw new BusinessException("Leave type not found in employee's leave balance");

        }
        long days = ChronoUnit.DAYS.between(leaveTrackerDto.getStartDate(), leaveTrackerDto.getEndDate()) + 1;
        if (days > leaveBalance.get().getLeaveBalance()) {
            throw new BusinessException("Insufficient leave balance for the requested leave type");
        }
        LeaveTracker leaveTracker = new LeaveTracker();
        leaveTracker.setEmployee(employee);
        leaveTracker.setStartDate(leaveTrackerDto.getStartDate());
        leaveTracker.setEndDate(leaveTrackerDto.getEndDate());
        leaveTracker.setLeaveType(leaveTrackerDto.getLeaveType());
        leaveTracker.setStatus("Pending");
        leaveTracker.setReason(leaveTrackerDto.getReason());

        LeaveTracker savedLeave = leaveTrackerRepository.save(leaveTracker);
        log.info("Leave applied successfully for employeeId: {}, leaveId: {}", employeeId, savedLeave.getId());

        actionItemService.createActionItem(employeeId,savedLeave,employee.getAssignedManagerId());
        return new LeaveTrackerResponse("Leave applied successfully", "Success");

    }
    @Override
    public LeaveTracker getLeaveById(Long id) {
        return leaveTrackerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Leave not found"));
    }

    @Override
    public List<LeaveTracker> getLeavesReportByEmployeeId(String employeeId, LocalDate startDate, LocalDate endDate) {
        return leaveTrackerRepository.findByEmployee_EmployeeIdAndStartDateGreaterThanEqualAndEndDateLessThanEqual(employeeId, startDate, endDate);
    }

    @Override
    public List<LeaveTracker> getLeaveHistory(String employeeId) {
        return leaveTrackerRepository.findByEmployee_EmployeeId(employeeId);
    }
}