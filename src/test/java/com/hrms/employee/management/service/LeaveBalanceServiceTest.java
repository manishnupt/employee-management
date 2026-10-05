package com.hrms.employee.management.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.Year;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dao.LeaveTransaction;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.repository.EmployeeLeaveBalanceRepository;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTrackerRepository;
import com.hrms.employee.management.repository.LeaveTransactionRepository;
import com.hrms.employee.management.utility.LeaveTransactionType;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveBalanceServiceTest {

    private static final String EMPLOYEE_ID = "e1";
    private static final Long LEAVE_ID = 42L;
    private static final String TYPE = "Earned";

    @Mock private LeaveTrackerRepository leaveTrackerRepository;
    @Mock private EmployeeLeaveBalanceRepository leaveBalanceRepository;
    @Mock private LeaveTransactionRepository leaveTransactionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private RestTemplate restTemplate;

    @InjectMocks private LeaveBalanceService service;

    private final int year = Year.now().getValue();
    private EmployeeLeaveBalance balance;

    @BeforeEach
    void setUp() {
        balance = new EmployeeLeaveBalance();
        balance.setEmployeeId(EMPLOYEE_ID);
        balance.setLeaveTypeName(TYPE);
        balance.setLeaveBalance(3);
        balance.setCarryForwardDays(1);
        balance.setYear(year);
        when(leaveBalanceRepository.findActiveForUpdate(EMPLOYEE_ID, TYPE, year)).thenReturn(Optional.of(balance));
    }

    @Test
    void deductsWorkingDaysOnly() {
        // Fri 9 Oct to Mon 12 Oct 2026: the weekend in between is not deducted.
        leave(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 12));

        service.deductLeaveFromEmployee(EMPLOYEE_ID, LEAVE_ID);

        assertEquals(2, balance.getAvailableDays());
        ArgumentCaptor<LeaveTransaction> txn = ArgumentCaptor.forClass(LeaveTransaction.class);
        verify(leaveTransactionRepository).save(txn.capture());
        assertEquals(LeaveTransactionType.DEBIT, txn.getValue().getTransactionType());
        assertEquals(2, txn.getValue().getDays());
        assertEquals(4, txn.getValue().getBalanceBefore());
        assertEquals(2, txn.getValue().getBalanceAfter());
    }

    @Test
    void usesTheWholeAvailableBalanceIncludingCarryForward() {
        leave(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 8));

        service.deductLeaveFromEmployee(EMPLOYEE_ID, LEAVE_ID);

        assertEquals(0, balance.getAvailableDays());
    }

    @Test
    void refusesWhenTheBalanceNoLongerCoversTheLeave() {
        // 5 working days against 4 available
        leave(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 9));

        assertThrows(BusinessException.class, () -> service.deductLeaveFromEmployee(EMPLOYEE_ID, LEAVE_ID));

        assertEquals(4, balance.getAvailableDays());
        verify(leaveBalanceRepository, never()).save(any());
        verify(leaveTransactionRepository, never()).save(any());
    }

    @Test
    void weekendOnlyLeaveDeductsNothing() {
        leave(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11));

        service.deductLeaveFromEmployee(EMPLOYEE_ID, LEAVE_ID);

        assertEquals(4, balance.getAvailableDays());
        verify(leaveTransactionRepository, never()).save(any());
    }

    @Test
    void refusesWhenThereIsNoBalanceRow() {
        leave(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5));
        when(leaveBalanceRepository.findActiveForUpdate(EMPLOYEE_ID, TYPE, year)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.deductLeaveFromEmployee(EMPLOYEE_ID, LEAVE_ID));
    }

    private void leave(LocalDate startDate, LocalDate endDate) {
        LeaveTracker leave = new LeaveTracker();
        leave.setLeaveType(TYPE);
        leave.setStartDate(startDate);
        leave.setEndDate(endDate);
        when(leaveTrackerRepository.findById(LEAVE_ID)).thenReturn(Optional.of(leave));
    }
}
