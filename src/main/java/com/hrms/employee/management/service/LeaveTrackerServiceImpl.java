package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Optional;

import com.hrms.employee.management.exceptions.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dto.LeaveTrackerDto;
import com.hrms.employee.management.dto.LeaveTrackerResponse;
import com.hrms.employee.management.repository.EmployeeLeaveBalanceRepository;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTrackerRepository;
import com.hrms.employee.management.utility.WorkingDays;

@Slf4j
@Service
public class LeaveTrackerServiceImpl implements LeaveTrackerService {

    private static final String STATUS_PENDING = "Pending";
    private static final String STATUS_APPROVED = "Approved";

    private final LeaveTrackerRepository leaveTrackerRepository;
    private final EmployeeRepository employeeRepository;
    private final ActionItemService actionItemService;
    private final EmployeeLeaveBalanceRepository employeeLeaveBalanceRepository;

    @Autowired
    private LeaveBalanceService leaveBalanceService;

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

        if (leaveTrackerDto.getStartDate() == null || leaveTrackerDto.getEndDate() == null
                || leaveTrackerDto.getEndDate().isBefore(leaveTrackerDto.getStartDate())) {
            throw new BusinessException("Leave end date must be on or after the start date");
        }
        // Same days the approval will deduct: weekends don't consume balance.
        long days = WorkingDays.between(leaveTrackerDto.getStartDate(), leaveTrackerDto.getEndDate());
        if (days == 0) {
            throw new BusinessException("The requested dates contain no working days");
        }

        // Same row the approval will deduct from: this year's open balance for the leave type.
        int currentYear = Year.now().getValue();
        List<EmployeeLeaveBalance> employeeLeaveBalance = employeeLeaveBalanceRepository
                .findByEmployeeIdAndYearAndIsActiveTrue(employeeId, currentYear);
        if (employeeLeaveBalance.isEmpty()) {
            throw new BusinessException("No active leave balance found for employee");
        }
        Optional<EmployeeLeaveBalance> leaveBalance = employeeLeaveBalance.stream()
                .filter(balance -> balance.getLeaveTypeName().equals(leaveTrackerDto.getLeaveType()))
                .findFirst();

        if (!leaveBalance.isPresent()) {
            throw new BusinessException("Leave type not found in employee's leave balance");

        }
        // Carried-forward days count towards what the employee can take.
        if (days > leaveBalance.get().getAvailableDays()) {
            throw new BusinessException("Insufficient leave balance for the requested leave type");
        }
        LeaveTracker leaveTracker = new LeaveTracker();
        leaveTracker.setEmployee(employee);
        leaveTracker.setStartDate(leaveTrackerDto.getStartDate());
        leaveTracker.setEndDate(leaveTrackerDto.getEndDate());
        leaveTracker.setLeaveType(leaveTrackerDto.getLeaveType());
        leaveTracker.setStatus(STATUS_PENDING);
        leaveTracker.setReason(leaveTrackerDto.getReason());

        LeaveTracker savedLeave = leaveTrackerRepository.save(leaveTracker);
        log.info("Leave applied successfully for employeeId: {}, leaveId: {}", employeeId, savedLeave.getId());

        Long actionItemId=actionItemService.createActionItem(employeeId,savedLeave,employee.getAssignedManagerId());
        if(actionItemId!=null) {
            log.info("Action item created successfully for leaveId: {}, actionItemId: {}", savedLeave.getId(), actionItemId);
            savedLeave.setLinkedActionItemId(actionItemId);
            leaveTrackerRepository.save(leaveTracker);
        }


        return new LeaveTrackerResponse("Leave applied successfully", "Success");

    }
    @Override
    public LeaveTracker getLeaveById(Long id) {
        return leaveTrackerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Leave not found"));
    }

    @Override
    public List<LeaveTracker> getLeavesReportByEmployeeId(String employeeId, LocalDate startDate, LocalDate endDate) {
        return leaveTrackerRepository.findOverlappingRange(employeeId, startDate, endDate);
    }

    @Override
    public List<LeaveTracker> getUnassignedLeaves(String employeeId) {
        return leaveTrackerRepository.findByEmployee_EmployeeIdAndLinkedActionItemIdIsNull(employeeId);
    }

    @Override
    public void saveLinkedActionItemId(Long id, Long actionItem) {
        LeaveTracker leaveById = getLeaveById(id);
        leaveById.setLinkedActionItemId(actionItem);
        leaveTrackerRepository.save(leaveById);
    }

    @Override
    @Transactional
    public LeaveTracker updateLeaveStatus(String employeeId, Long id, String status) {
        LeaveTracker leaveTracker = leaveTrackerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Leave not found"));

        if (!leaveTracker.getEmployee().getEmployeeId().equals(employeeId)) {
            throw new RuntimeException("Leave does not belong to the specified employee");
        }
        String currentStatus = leaveTracker.getStatus();
        if (status.equalsIgnoreCase(currentStatus)) {
            // Repeat of a decision already recorded (e.g. a retried request): nothing to change.
            log.info("Leave {} is already {}. Ignoring status update.", id, currentStatus);
            return leaveTracker;
        }
        if (!STATUS_PENDING.equalsIgnoreCase(currentStatus)) {
            throw new BusinessException("Leave is already " + currentStatus + " and cannot be changed to " + status);
        }
        // Only an approval consumes balance; a rejection leaves it untouched.
        if (STATUS_APPROVED.equalsIgnoreCase(status)) {
            leaveBalanceService.deductLeaveFromEmployee(employeeId, id);
        }

        leaveTracker.setStatus(status);
        return leaveTrackerRepository.save(leaveTracker);
    }

    @Override
    public List<LeaveTracker> getLeaveHistory(String employeeId) {
        return leaveTrackerRepository.findByEmployee_EmployeeId(employeeId);
    }
}