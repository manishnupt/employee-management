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
import com.hrms.employee.management.dao.EmployeeWfhBalance;
import com.hrms.employee.management.dao.WFHTransaction;
import com.hrms.employee.management.dto.WFHDisbursalDto;
import com.hrms.employee.management.repository.DisbursalRunRepository;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.EmployeeWfhBalanceRepository;
import com.hrms.employee.management.repository.WFHTransactionRepository;
import com.hrms.employee.management.utility.TenantJobRunner;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WFHDisbursalSchedulerServiceTest {

    private static final String TYPE = "Standard";

    @Mock private RestTemplate restTemplate;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeWfhBalanceRepository employeeWfhBalanceRepository;
    @Mock private WFHTransactionRepository wfhTransactionRepository;
    @Mock private DisbursalRunRepository disbursalRunRepository;
    @Mock private TenantJobRunner tenantJobRunner;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private WFHDisbursalSchedulerService service;

    private final int year = LocalDate.now().getYear();
    private final List<EmployeeWfhBalance> existingBalances = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReflectionTestUtils.setField(service, "companyServiceBaseUrl", "http://company");
        ReflectionTestUtils.setField(service, "defaultTenant", "acme");
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(WFHDisbursalDto[].class)))
                .thenReturn(ResponseEntity.ok(new WFHDisbursalDto[] { type(TYPE, 12) }));
        when(disbursalRunRepository.claim(any(), any(), any(), any(), any(), anyDouble())).thenReturn(1);
        when(employeeWfhBalanceRepository.findByWfhTypeNameAndYearAndIsActiveTrue(TYPE, year)).thenReturn(existingBalances);
        when(employeeRepository.findAll()).thenReturn(List.of(employee("e1", "Active", false, lastYear())));
    }

    @Test
    void addsToTheExistingRowInsteadOfInsertingANewOne() {
        EmployeeWfhBalance existing = balance(7L, "e1", 3);
        existingBalances.add(existing);

        service.disburseYearlyWFH();

        List<EmployeeWfhBalance> saved = savedBalances();
        assertEquals(1, saved.size());
        assertSame(existing, saved.get(0));
        assertEquals(15, existing.getWfhBalance());
        List<WFHTransaction> txns = savedTransactions();
        assertEquals(1, txns.size());
        assertEquals("CREDIT", txns.get(0).getTransactionType());
        assertEquals(12, txns.get(0).getDays());
        assertEquals(year, txns.get(0).getYear());
        assertEquals(3, txns.get(0).getBalanceBefore());
        assertEquals(15, txns.get(0).getBalanceAfter());
        assertEquals("YEARLY disbursal " + year, txns.get(0).getReason());
    }

    @Test
    void createsACurrentYearRowWhenTheEmployeeHasNone() {
        service.disburseYearlyWFH();

        EmployeeWfhBalance created = savedBalances().get(0);
        assertNull(created.getId());
        assertEquals("e1", created.getEmployeeId());
        assertEquals(TYPE, created.getWfhTypeName());
        assertEquals(12, created.getWfhBalance());
        assertEquals(year, created.getYear());
        assertTrue(created.isActive());
    }

    @Test
    void creditsTheOldestRowWhenDuplicatesExist() {
        EmployeeWfhBalance newer = balance(9L, "e1", 1);
        EmployeeWfhBalance older = balance(4L, "e1", 2);
        existingBalances.add(newer);
        existingBalances.add(older);

        service.disburseYearlyWFH();

        assertSame(older, savedBalances().get(0));
        assertEquals(14, older.getWfhBalance());
        assertEquals(1, newer.getWfhBalance());
    }

    @Test
    void skipsDeletedInactiveAndCurrentPeriodJoiners() {
        when(employeeRepository.findAll()).thenReturn(List.of(
                employee("e1", "Active", false, lastYear()),
                employee("deleted", "Active", true, lastYear()),
                employee("exited", "Exited", false, lastYear()),
                employee("joiner", "Active", false, LocalDateTime.now())));

        service.disburseYearlyWFH();

        List<EmployeeWfhBalance> saved = savedBalances();
        assertEquals(1, saved.size());
        assertEquals("e1", saved.get(0).getEmployeeId());
    }

    @Test
    void doesNothingWhenThePeriodIsAlreadyDisbursed() {
        when(disbursalRunRepository.claim(any(), any(), any(), any(), any(), anyDouble())).thenReturn(0);

        service.disburseYearlyWFH();

        verify(employeeWfhBalanceRepository, never()).saveAll(any());
        verify(wfhTransactionRepository, never()).saveAll(any());
    }

    @Test
    void skipsATypeWithoutAName() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(WFHDisbursalDto[].class)))
                .thenReturn(ResponseEntity.ok(new WFHDisbursalDto[] { type(null, 12) }));

        service.disburseYearlyWFH();

        verify(disbursalRunRepository, never()).claim(any(), any(), any(), any(), any(), anyDouble());
    }

    @Test
    void lapsesTheUnusedBalanceBeforeCreditingWhenTheTypeHasNoCarryForward() {
        companyReturns(type(TYPE, 12, false, null));
        EmployeeWfhBalance existing = balance(7L, "e1", 3);
        existingBalances.add(existing);

        service.disburseYearlyWFH();

        assertEquals(1, savedBalances().size());
        assertEquals(12, existing.getWfhBalance());
        List<WFHTransaction> txns = savedTransactions();
        assertEquals(2, txns.size());
        assertEquals("LAPSE", txns.get(0).getTransactionType());
        assertEquals(3, txns.get(0).getDays());
        assertEquals(3, txns.get(0).getBalanceBefore());
        assertEquals(0, txns.get(0).getBalanceAfter());
        assertEquals("CREDIT", txns.get(1).getTransactionType());
        assertEquals(0, txns.get(1).getBalanceBefore());
        assertEquals(12, txns.get(1).getBalanceAfter());
    }

    @Test
    void keepsTheUnusedBalanceWhenCarryForwardHasNoCap() {
        companyReturns(type(TYPE, 12, true, null));
        EmployeeWfhBalance existing = balance(7L, "e1", 3);
        existingBalances.add(existing);

        service.disburseYearlyWFH();

        assertEquals(15, existing.getWfhBalance());
        assertEquals(1, savedTransactions().size());
    }

    @Test
    void lapsesOnlyWhatExceedsTheCarryForwardCap() {
        companyReturns(type(TYPE, 12, true, 2.0));
        EmployeeWfhBalance existing = balance(7L, "e1", 5);
        existingBalances.add(existing);

        service.disburseYearlyWFH();

        assertEquals(14, existing.getWfhBalance());
        List<WFHTransaction> txns = savedTransactions();
        assertEquals("LAPSE", txns.get(0).getTransactionType());
        assertEquals(3, txns.get(0).getDays());
    }

    @Test
    void lapsesEvenWhenThePeriodCreditsNothing() {
        companyReturns(type(TYPE, 0, false, null));
        EmployeeWfhBalance existing = balance(7L, "e1", 3);
        existingBalances.add(existing);

        service.disburseYearlyWFH();

        assertEquals(0, existing.getWfhBalance());
        List<WFHTransaction> txns = savedTransactions();
        assertEquals(1, txns.size());
        assertEquals("LAPSE", txns.get(0).getTransactionType());
    }

    @Test
    void doesNotLapseTheProratedBalanceOfSomeoneWhoJoinedThisPeriod() {
        companyReturns(type(TYPE, 12, false, null));
        when(employeeRepository.findAll()).thenReturn(List.of(employee("joiner", "Active", false, LocalDateTime.now())));
        EmployeeWfhBalance prorated = balance(7L, "joiner", 1);
        existingBalances.add(prorated);

        service.disburseMonthlyWFH();

        assertEquals(1, prorated.getWfhBalance());
        assertTrue(savedTransactions().isEmpty());
    }

    private void companyReturns(WFHDisbursalDto wfhType) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(WFHDisbursalDto[].class)))
                .thenReturn(ResponseEntity.ok(new WFHDisbursalDto[] { wfhType }));
    }

    private static WFHDisbursalDto type(String name, int totalDays, Boolean carryForward, Double maxCarryForwardDays) {
        WFHDisbursalDto dto = type(name, totalDays);
        dto.setCarryForward(carryForward);
        dto.setMaxCarryForwardDays(maxCarryForwardDays);
        return dto;
    }

    private LocalDateTime lastYear() {
        return LocalDateTime.of(year - 1, 6, 1, 9, 0);
    }

    private static WFHDisbursalDto type(String name, int totalDays) {
        WFHDisbursalDto dto = new WFHDisbursalDto();
        dto.setWfhType(name);
        dto.setTotalDays(totalDays);
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

    private EmployeeWfhBalance balance(Long id, String employeeId, int days) {
        EmployeeWfhBalance balance = new EmployeeWfhBalance();
        balance.setId(id);
        balance.setEmployeeId(employeeId);
        balance.setWfhTypeName(TYPE);
        balance.setWfhBalance(days);
        balance.setYear(year);
        return balance;
    }

    @SuppressWarnings("unchecked")
    private List<EmployeeWfhBalance> savedBalances() {
        ArgumentCaptor<List<EmployeeWfhBalance>> captor = ArgumentCaptor.forClass(List.class);
        verify(employeeWfhBalanceRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<WFHTransaction> savedTransactions() {
        ArgumentCaptor<List<WFHTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(wfhTransactionRepository).saveAll(captor.capture());
        return captor.getValue();
    }
}
