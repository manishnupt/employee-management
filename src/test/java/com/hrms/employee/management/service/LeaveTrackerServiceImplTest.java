package com.hrms.employee.management.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dto.LeaveTrackerDto;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.repository.EmployeeLeaveBalanceRepository;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTrackerRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveTrackerServiceImplTest {

    private static final String EMPLOYEE_ID = "e1";
    private static final Long LEAVE_ID = 42L;

    @Mock private LeaveTrackerRepository leaveTrackerRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ActionItemService actionItemService;
    @Mock private EmployeeLeaveBalanceRepository employeeLeaveBalanceRepository;
    @Mock private LeaveBalanceService leaveBalanceService;

    private LeaveTrackerServiceImpl service;
    private LeaveTracker leave;

    @BeforeEach
    void setUp() {
        service = new LeaveTrackerServiceImpl(leaveTrackerRepository, employeeRepository, actionItemService,
                employeeLeaveBalanceRepository);
        ReflectionTestUtils.setField(service, "leaveBalanceService", leaveBalanceService);

        Employee employee = new Employee();
        employee.setEmployeeId(EMPLOYEE_ID);
        leave = new LeaveTracker();
        leave.setEmployee(employee);
        leave.setStatus("Pending");
        when(leaveTrackerRepository.findById(LEAVE_ID)).thenReturn(Optional.of(leave));
        when(leaveTrackerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void approvingDeductsBalance() {
        LeaveTracker updated = service.updateLeaveStatus(EMPLOYEE_ID, LEAVE_ID, "APPROVED");

        verify(leaveBalanceService).deductLeaveFromEmployee(EMPLOYEE_ID, LEAVE_ID);
        assertEquals("APPROVED", updated.getStatus());
    }

    @Test
    void rejectingDoesNotDeductBalance() {
        LeaveTracker updated = service.updateLeaveStatus(EMPLOYEE_ID, LEAVE_ID, "Rejected");

        verify(leaveBalanceService, never()).deductLeaveFromEmployee(any(), any());
        assertEquals("Rejected", updated.getStatus());
    }

    @Test
    void approvingAgainDoesNotDeductTwice() {
        leave.setStatus("Approved");

        service.updateLeaveStatus(EMPLOYEE_ID, LEAVE_ID, "APPROVED");

        verify(leaveBalanceService, never()).deductLeaveFromEmployee(any(), any());
        verify(leaveTrackerRepository, never()).save(any());
    }

    @Test
    void decidedLeaveCannotBeChanged() {
        leave.setStatus("Rejected");

        assertThrows(BusinessException.class, () -> service.updateLeaveStatus(EMPLOYEE_ID, LEAVE_ID, "Approved"));

        verify(leaveBalanceService, never()).deductLeaveFromEmployee(any(), any());
        assertEquals("Rejected", leave.getStatus());
    }

    @Test
    void leaveOfAnotherEmployeeIsRefused() {
        assertThrows(RuntimeException.class, () -> service.updateLeaveStatus("someone-else", LEAVE_ID, "Approved"));

        verify(leaveBalanceService, never()).deductLeaveFromEmployee(any(), any());
    }

    @Test
    void applyCountsWorkingDaysAgainstThisYearsBalance() {
        stubApply(2);

        // Fri 9 Oct to Mon 12 Oct 2026 is 2 working days
        service.applyLeave(EMPLOYEE_ID, request(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 12)));

        verify(leaveTrackerRepository, atLeastOnce()).save(any());
    }

    @Test
    void applyRefusesMoreWorkingDaysThanAvailable() {
        stubApply(2);

        assertThrows(BusinessException.class,
                () -> service.applyLeave(EMPLOYEE_ID, request(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 12))));

        verify(leaveTrackerRepository, never()).save(any());
    }

    @Test
    void applyRefusesReversedOrWeekendOnlyDates() {
        stubApply(10);

        assertThrows(BusinessException.class,
                () -> service.applyLeave(EMPLOYEE_ID, request(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 5))));
        assertThrows(BusinessException.class,
                () -> service.applyLeave(EMPLOYEE_ID, request(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11))));

        verify(leaveTrackerRepository, never()).save(any());
    }

    private void stubApply(double availableDays) {
        EmployeeLeaveBalance balance = new EmployeeLeaveBalance();
        balance.setLeaveTypeName("Earned");
        balance.setLeaveBalance(availableDays);
        when(employeeRepository.findById(EMPLOYEE_ID)).thenReturn(Optional.of(leave.getEmployee()));
        when(employeeLeaveBalanceRepository.findByEmployeeIdAndYearAndIsActiveTrue(EMPLOYEE_ID, Year.now().getValue()))
                .thenReturn(List.of(balance));
    }

    private static LeaveTrackerDto request(LocalDate startDate, LocalDate endDate) {
        LeaveTrackerDto dto = new LeaveTrackerDto();
        dto.setLeaveType("Earned");
        dto.setStartDate(startDate);
        dto.setEndDate(endDate);
        return dto;
    }
}
