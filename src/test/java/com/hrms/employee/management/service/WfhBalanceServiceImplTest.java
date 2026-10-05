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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeWfhBalance;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.dao.WFHTransaction;
import com.hrms.employee.management.dto.WfhType;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.EmployeeWfhBalanceRepository;
import com.hrms.employee.management.repository.WFHTrackerRepository;
import com.hrms.employee.management.repository.WFHTransactionRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WfhBalanceServiceImplTest {

    private static final String EMPLOYEE_ID = "e1";
    private static final Long TRACKER_ID = 7L;

    @Mock private EmployeeWfhBalanceRepository employeeWfhBalanceRepository;
    @Mock private WFHTrackerRepository wfhTrackerRepository;
    @Mock private WFHTransactionRepository wfhTransactionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private RestTemplate restTemplate;

    @InjectMocks private WfhBalanceServiceImpl service;

    private final int year = Year.now().getValue();
    private Employee employee;

    @BeforeEach
    void setUp() {
        employee = new Employee();
        employee.setEmployeeId(EMPLOYEE_ID);
        when(employeeWfhBalanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void deductDrawsAcrossBalancesAndRecordsEachInTheLedger() {
        EmployeeWfhBalance adhoc = balance("Adhoc", 1);
        EmployeeWfhBalance standard = balance("Standard", 4);
        balances(standard, adhoc);
        tracker("PENDING", true, 3);

        service.deductWfhBalance(EMPLOYEE_ID, TRACKER_ID);

        assertEquals(0, adhoc.getWfhBalance());
        assertEquals(2, standard.getWfhBalance());
        List<WFHTransaction> txns = savedTransactions(2);
        assertTxn(txns.get(0), "Adhoc", "DEBIT", 1, 1, 0, "WFH #7");
        assertTxn(txns.get(1), "Standard", "DEBIT", 2, 4, 2, "WFH #7");
    }

    @Test
    void deductRefusesWhenTheBalanceIsShort() {
        EmployeeWfhBalance standard = balance("Standard", 2);
        balances(standard);
        tracker("PENDING", true, 3);

        assertThrows(RuntimeException.class, () -> service.deductWfhBalance(EMPLOYEE_ID, TRACKER_ID));

        assertEquals(2, standard.getWfhBalance());
        verify(wfhTransactionRepository, never()).save(any());
    }

    @Test
    void refundCreditsTheDaysBackOnce() {
        EmployeeWfhBalance standard = balance("Standard", 2);
        balances(standard);
        tracker("APPROVED", true, 3);

        service.disburseWfhBalance(EMPLOYEE_ID, TRACKER_ID);

        assertEquals(5, standard.getWfhBalance());
        assertTxn(savedTransactions(1).get(0), "Standard", "CREDIT", 3, 2, 5, "Refund WFH #7");
    }

    @Test
    void refundIsRefusedTheSecondTime() {
        EmployeeWfhBalance standard = balance("Standard", 2);
        balances(standard);
        tracker("APPROVED", true, 3);
        when(wfhTransactionRepository.existsByEmployeeIdAndReason(EMPLOYEE_ID, "Refund WFH #7")).thenReturn(true);

        assertThrows(BusinessException.class, () -> service.disburseWfhBalance(EMPLOYEE_ID, TRACKER_ID));

        assertEquals(2, standard.getWfhBalance());
    }

    @Test
    void refundIsRefusedWhenNothingWasDeducted() {
        EmployeeWfhBalance standard = balance("Standard", 2);
        balances(standard);

        tracker("PENDING", true, 3);
        assertThrows(BusinessException.class, () -> service.disburseWfhBalance(EMPLOYEE_ID, TRACKER_ID));
        tracker("APPROVED", false, 3);
        assertThrows(BusinessException.class, () -> service.disburseWfhBalance(EMPLOYEE_ID, TRACKER_ID));
        assertThrows(BusinessException.class, () -> service.disburseWfhBalance("someone-else", TRACKER_ID));

        assertEquals(2, standard.getWfhBalance());
        verify(wfhTransactionRepository, never()).save(any());
    }

    @Test
    void initLedgerMatchesTheStoredBalance() {
        when(employeeRepository.findAll()).thenReturn(List.of(employee));
        WfhType type = new WfhType();
        type.setName("Standard");
        type.setTotalDays(10);
        type.setDisbursalFrequency(com.hrms.employee.management.utility.DisbursalFrequency.YEARLY);

        service.initializeWfhBalanceForNewWfhType(type);

        ArgumentCaptor<EmployeeWfhBalance> saved = ArgumentCaptor.forClass(EmployeeWfhBalance.class);
        verify(employeeWfhBalanceRepository).save(saved.capture());
        int stored = saved.getValue().getWfhBalance();
        assertTxn(savedTransactions(1).get(0), "Standard", "INITIALIZATION", stored, 0, stored, "NEW_WFH_TYPE_INITIALIZATION");
    }

    private EmployeeWfhBalance balance(String type, int days) {
        EmployeeWfhBalance balance = new EmployeeWfhBalance();
        balance.setEmployeeId(EMPLOYEE_ID);
        balance.setWfhTypeName(type);
        balance.setWfhBalance(days);
        balance.setYear(year);
        return balance;
    }

    private void balances(EmployeeWfhBalance... balances) {
        when(employeeWfhBalanceRepository.findByEmployeeIdAndYearAndIsActiveTrue(EMPLOYEE_ID, year))
                .thenReturn(List.of(balances));
    }

    private void tracker(String status, boolean deductWfhBalance, int days) {
        WFHTracker tracker = new WFHTracker();
        tracker.setEmployee(employee);
        tracker.setStatus(status);
        tracker.setDeductWfhBalance(deductWfhBalance);
        tracker.setStartDate(LocalDate.of(2026, 10, 5));
        tracker.setEndDate(LocalDate.of(2026, 10, 5).plusDays(days - 1));
        when(wfhTrackerRepository.findById(TRACKER_ID)).thenReturn(Optional.of(tracker));
    }

    private List<WFHTransaction> savedTransactions(int count) {
        ArgumentCaptor<WFHTransaction> captor = ArgumentCaptor.forClass(WFHTransaction.class);
        verify(wfhTransactionRepository, times(count)).save(captor.capture());
        return captor.getAllValues();
    }

    private void assertTxn(WFHTransaction txn, String type, String transactionType, double days,
                           double before, double after, String reason) {
        assertEquals(EMPLOYEE_ID, txn.getEmployeeId());
        assertEquals(type, txn.getWfhTypeName());
        assertEquals(transactionType, txn.getTransactionType());
        assertEquals(days, txn.getDays());
        assertEquals(year, txn.getYear());
        assertEquals(before, txn.getBalanceBefore());
        assertEquals(after, txn.getBalanceAfter());
        assertEquals(reason, txn.getReason());
    }
}
