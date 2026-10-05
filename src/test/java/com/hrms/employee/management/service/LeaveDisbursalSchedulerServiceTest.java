package com.hrms.employee.management.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import com.hrms.employee.management.dao.LeaveTransaction;
import com.hrms.employee.management.dto.LeaveDisbursalDto;
import com.hrms.employee.management.repository.DisbursalRunRepository;
import com.hrms.employee.management.repository.EmployeeLeaveBalanceRepository;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTransactionRepository;
import com.hrms.employee.management.utility.LeaveTransactionType;
import com.hrms.employee.management.utility.TenantJobRunner;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveDisbursalSchedulerServiceTest {

    private static final String TYPE = "Casual";

    @Mock private RestTemplate restTemplate;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeLeaveBalanceRepository leaveBalanceRepository;
    @Mock private LeaveTransactionRepository leaveTransactionRepository;
    @Mock private DisbursalRunRepository disbursalRunRepository;
    @Mock private TenantJobRunner tenantJobRunner;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private LeaveYearEndRolloverService leaveYearEndRolloverService;

    @InjectMocks private LeaveDisbursalSchedulerService service;

    private final int year = LocalDate.now().getYear();
    private final List<EmployeeLeaveBalance> existingBalances = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReflectionTestUtils.setField(service, "companyServiceBaseUrl", "http://company");
        ReflectionTestUtils.setField(service, "defaultTenant", "acme");
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        when(disbursalRunRepository.claim(any(), any(), any(), any(), any(), anyDouble())).thenReturn(1);
        when(leaveBalanceRepository.findByLeaveTypeNameAndYearAndIsActiveTrue(TYPE, year)).thenReturn(existingBalances);
        when(employeeRepository.findAll()).thenReturn(List.of(employee("e1", "Active", false, lastYear())));
    }

    @Test
    void lapsesTheUnusedBalanceBeforeCreditingWhenTheTypeHasNoCarryForward() {
        companyReturns(type(24, false, null));
        EmployeeLeaveBalance existing = balance("e1", 1.5, 0);
        existingBalances.add(existing);

        service.disburseMonthlyLeave();

        assertEquals(2.0, existing.getLeaveBalance());
        assertEquals(0.0, existing.getCarryForwardDays());
        List<LeaveTransaction> ledger = savedTransactions();
        assertEquals(2, ledger.size());
        assertEquals(LeaveTransactionType.LAPSE, ledger.get(0).getTransactionType());
        assertEquals(1.5, ledger.get(0).getDays());
        assertEquals(1.5, ledger.get(0).getBalanceBefore());
        assertEquals(0.0, ledger.get(0).getBalanceAfter());
        assertEquals(LeaveTransactionType.CREDIT, ledger.get(1).getTransactionType());
        assertEquals(0.0, ledger.get(1).getBalanceBefore());
        assertEquals(2.0, ledger.get(1).getBalanceAfter());
    }

    @Test
    void keepsTheWholeUnusedBalanceWhenCarryForwardHasNoCap() {
        companyReturns(type(24, true, null));
        EmployeeLeaveBalance existing = balance("e1", 1.5, 1);
        existingBalances.add(existing);

        service.disburseMonthlyLeave();

        assertEquals(2.0, existing.getLeaveBalance());
        assertEquals(2.5, existing.getCarryForwardDays());
        List<LeaveTransaction> ledger = savedTransactions();
        assertEquals(1, ledger.size());
        assertEquals(LeaveTransactionType.CREDIT, ledger.get(0).getTransactionType());
        assertEquals(2.5, ledger.get(0).getBalanceBefore());
        assertEquals(4.5, ledger.get(0).getBalanceAfter());
    }

    @Test
    void lapsesOnlyWhatExceedsTheCarryForwardCap() {
        companyReturns(type(24, true, 1.0));
        EmployeeLeaveBalance existing = balance("e1", 2, 1);
        existingBalances.add(existing);

        service.disburseMonthlyLeave();

        assertEquals(2.0, existing.getLeaveBalance());
        assertEquals(1.0, existing.getCarryForwardDays());
        List<LeaveTransaction> ledger = savedTransactions();
        assertEquals(LeaveTransactionType.LAPSE, ledger.get(0).getTransactionType());
        assertEquals(2.0, ledger.get(0).getDays());
        assertEquals(3.0, ledger.get(1).getBalanceAfter());
    }

    @Test
    void doesNotLapseTheProratedBalanceOfSomeoneWhoJoinedThisPeriod() {
        companyReturns(type(24, false, null));
        when(employeeRepository.findAll()).thenReturn(List.of(employee("joiner", "Active", false, LocalDateTime.now())));
        EmployeeLeaveBalance prorated = balance("joiner", 1.25, 0);
        existingBalances.add(prorated);

        service.disburseMonthlyLeave();

        assertEquals(1.25, prorated.getLeaveBalance());
        assertTrue(savedTransactions().isEmpty());
    }

    @Test
    void lapsesForAnEmployeeWhoNoLongerAccrues() {
        companyReturns(type(24, false, null));
        when(employeeRepository.findAll()).thenReturn(List.of(employee("exited", "Exited", false, lastYear())));
        EmployeeLeaveBalance existing = balance("exited", 3, 0);
        existingBalances.add(existing);

        service.disburseMonthlyLeave();

        assertEquals(0.0, existing.getAvailableDays());
        List<LeaveTransaction> ledger = savedTransactions();
        assertEquals(1, ledger.size());
        assertEquals(LeaveTransactionType.LAPSE, ledger.get(0).getTransactionType());
    }

    private void companyReturns(LeaveDisbursalDto leaveType) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(LeaveDisbursalDto[].class)))
                .thenReturn(ResponseEntity.ok(new LeaveDisbursalDto[] { leaveType }));
    }

    private LocalDateTime lastYear() {
        return LocalDateTime.of(year - 1, 6, 1, 9, 0);
    }

    private static LeaveDisbursalDto type(int totalDays, boolean carryForward, Double maxCarryForwardDays) {
        LeaveDisbursalDto dto = new LeaveDisbursalDto();
        dto.setName(TYPE);
        dto.setTotalDays(totalDays);
        dto.setCarryForward(carryForward);
        dto.setMaxCarryForwardDays(maxCarryForwardDays);
        return dto;
    }

    private static Employee employee(String id, String jobStatus, boolean deleted, LocalDateTime createdAt) {
        Employee employee = new Employee();
        employee.setEmployeeId(id);
        employee.setJobStatus(jobStatus);
        employee.setDeleted(deleted);
        employee.setCreatedAt(createdAt);
        return employee;
    }

    private EmployeeLeaveBalance balance(String employeeId, double leaveBalance, double carryForwardDays) {
        EmployeeLeaveBalance balance = new EmployeeLeaveBalance();
        balance.setEmployeeId(employeeId);
        balance.setLeaveTypeName(TYPE);
        balance.setLeaveBalance(leaveBalance);
        balance.setCarryForwardDays(carryForwardDays);
        balance.setYear(year);
        balance.setActive(true);
        return balance;
    }

    @SuppressWarnings("unchecked")
    private List<LeaveTransaction> savedTransactions() {
        ArgumentCaptor<List<LeaveTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(leaveTransactionRepository).saveAll(captor.capture());
        return captor.getValue();
    }
}
