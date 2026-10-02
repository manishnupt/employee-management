package com.hrms.employee.management.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveYearEndRolloverServiceTest {

    private static final String TENANT = "acme";

    @Mock private RestTemplate restTemplate;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeLeaveBalanceRepository leaveBalanceRepository;
    @Mock private LeaveTransactionRepository leaveTransactionRepository;
    @Mock private DisbursalRunRepository disbursalRunRepository;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private LeaveYearEndRolloverService service;

    private final List<LeaveDisbursalDto> yearlyTypes = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReflectionTestUtils.setField(service, "companyServiceBaseUrl", "http://company");
        doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(LeaveDisbursalDto[].class)))
                .thenAnswer(inv -> ResponseEntity.ok(((String) inv.getArgument(0)).endsWith("/yearly")
                        ? yearlyTypes.toArray(new LeaveDisbursalDto[0]) : new LeaveDisbursalDto[0]));
        when(disbursalRunRepository.claim(any(), any(), any(), any(), any(), anyDouble())).thenReturn(1);
        when(employeeRepository.findAll()).thenReturn(List.of(employee("e1", false), employee("e2", true)));
        when(leaveBalanceRepository.findAllByYearAndIsActiveTrue(2027)).thenReturn(new ArrayList<>());
    }

    @Test
    void carriesFullBalanceWhenCarryForwardAllowedWithoutCap() {
        yearlyTypes.add(type("Earned", true, null));
        EmployeeLeaveBalance closing = balance("e1", "Earned", 2026, 3, 2);
        when(leaveBalanceRepository.findByYearLessThanAndIsActiveTrueOrderByYearAsc(2027)).thenReturn(List.of(closing));

        service.rolloverIfNeeded(TENANT, 2027);

        assertFalse(closing.isActive());
        EmployeeLeaveBalance opening = single(savedBalances(1));
        assertEquals(2027, opening.getYear());
        assertEquals(0, opening.getLeaveBalance());
        assertEquals(5, opening.getCarryForwardDays());
        List<LeaveTransaction> txns = savedTransactions();
        assertEquals(2, txns.size());
        assertTxn(txns.get(0), LeaveTransactionType.CARRY_FORWARD_OUT, 5, 2026, 5, 0);
        assertTxn(txns.get(1), LeaveTransactionType.CARRY_FORWARD, 5, 2027, 0, 5);
    }

    @Test
    void lapsesAboveCapAndCarriesTheRest() {
        yearlyTypes.add(type("Earned", true, 2.0));
        when(leaveBalanceRepository.findByYearLessThanAndIsActiveTrueOrderByYearAsc(2027))
                .thenReturn(List.of(balance("e1", "Earned", 2026, 5, 0)));

        service.rolloverIfNeeded(TENANT, 2027);

        assertEquals(2, single(savedBalances(1)).getCarryForwardDays());
        List<LeaveTransaction> txns = savedTransactions();
        assertEquals(3, txns.size());
        assertTxn(txns.get(0), LeaveTransactionType.LAPSE, 3, 2026, 5, 2);
        assertTxn(txns.get(1), LeaveTransactionType.CARRY_FORWARD_OUT, 2, 2026, 2, 0);
        assertTxn(txns.get(2), LeaveTransactionType.CARRY_FORWARD, 2, 2027, 0, 2);
    }

    @Test
    void lapsesEverythingWhenTypeDoesNotCarryForwardButStillOpensNewYear() {
        yearlyTypes.add(type("Casual", false, null));
        when(leaveBalanceRepository.findByYearLessThanAndIsActiveTrueOrderByYearAsc(2027))
                .thenReturn(List.of(balance("e1", "Casual", 2026, 4, 0)));

        service.rolloverIfNeeded(TENANT, 2027);

        EmployeeLeaveBalance opening = single(savedBalances(1));
        assertEquals(0, opening.getAvailableDays());
        List<LeaveTransaction> txns = savedTransactions();
        assertEquals(1, txns.size());
        assertTxn(txns.get(0), LeaveTransactionType.LAPSE, 4, 2026, 4, 0);
    }

    @Test
    void lapsesForDeletedEmployeeAndDiscontinuedTypeWithoutOpeningRows() {
        yearlyTypes.add(type("Earned", true, null));
        EmployeeLeaveBalance deletedEmployee = balance("e2", "Earned", 2026, 6, 0);
        EmployeeLeaveBalance discontinuedType = balance("e1", "Old", 2026, 1, 0);
        when(leaveBalanceRepository.findByYearLessThanAndIsActiveTrueOrderByYearAsc(2027))
                .thenReturn(List.of(deletedEmployee, discontinuedType));

        service.rolloverIfNeeded(TENANT, 2027);

        assertFalse(deletedEmployee.isActive());
        assertFalse(discontinuedType.isActive());
        assertTrue(savedBalances(1).isEmpty());
        List<LeaveTransaction> txns = savedTransactions();
        assertEquals(2, txns.size());
        assertTxn(txns.get(0), LeaveTransactionType.LAPSE, 6, 2026, 6, 0);
        assertEquals("Employee deleted or not found", txns.get(0).getReason());
        assertTxn(txns.get(1), LeaveTransactionType.LAPSE, 1, 2026, 1, 0);
        assertEquals("Leave type discontinued", txns.get(1).getReason());
    }

    @Test
    void negativeBalanceIsClosedWithoutCarryOrLapse() {
        yearlyTypes.add(type("Earned", true, null));
        EmployeeLeaveBalance closing = balance("e1", "Earned", 2026, -2, 0);
        when(leaveBalanceRepository.findByYearLessThanAndIsActiveTrueOrderByYearAsc(2027)).thenReturn(List.of(closing));

        service.rolloverIfNeeded(TENANT, 2027);

        assertFalse(closing.isActive());
        assertEquals(0, single(savedBalances(1)).getAvailableDays());
        assertTrue(savedTransactions().isEmpty());
    }

    @Test
    void addsCarryToExistingNewYearRow() {
        yearlyTypes.add(type("Earned", true, null));
        EmployeeLeaveBalance existing2027 = balance("e1", "Earned", 2027, 2, 0);
        when(leaveBalanceRepository.findAllByYearAndIsActiveTrue(2027)).thenReturn(List.of(existing2027));
        when(leaveBalanceRepository.findByYearLessThanAndIsActiveTrueOrderByYearAsc(2027))
                .thenReturn(List.of(balance("e1", "Earned", 2026, 3, 0)));

        service.rolloverIfNeeded(TENANT, 2027);

        assertSame(existing2027, single(savedBalances(1)));
        assertEquals(3, existing2027.getCarryForwardDays());
        assertTxn(savedTransactions().get(1), LeaveTransactionType.CARRY_FORWARD, 3, 2027, 2, 5);
    }

    @Test
    void skipsWhenAlreadyRolledOver() {
        when(disbursalRunRepository.existsByTenantIdAndKindAndTypeNameAndPeriodKey(TENANT, "LEAVE_ROLLOVER", "*", "2027"))
                .thenReturn(true);

        service.rolloverIfNeeded(TENANT, 2027);

        verifyNoInteractions(restTemplate, transactionTemplate, leaveBalanceRepository, leaveTransactionRepository);
    }

    @Test
    void skipsWhenAnotherReplicaClaimedFirst() {
        yearlyTypes.add(type("Earned", true, null));
        when(disbursalRunRepository.claim(any(), any(), any(), any(), any(), anyDouble())).thenReturn(0);

        service.rolloverIfNeeded(TENANT, 2027);

        verifyNoInteractions(leaveBalanceRepository, leaveTransactionRepository);
    }

    @Test
    void refusesToRollOverWhenCompanyServiceReturnsNoTypes() {
        assertThrows(IllegalStateException.class, () -> service.rolloverIfNeeded(TENANT, 2027));
        verifyNoInteractions(transactionTemplate, leaveBalanceRepository);
    }

    @Test
    void deductUsesCarryForwardFirst() {
        EmployeeLeaveBalance balance = balance("e1", "Earned", 2027, 4, 3);
        balance.deductDays(5);
        assertEquals(0, balance.getCarryForwardDays());
        assertEquals(2, balance.getLeaveBalance());
        assertEquals(2, balance.getRemainingDays());
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private List<EmployeeLeaveBalance> savedBalances(int call) {
        ArgumentCaptor<Iterable<EmployeeLeaveBalance>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(leaveBalanceRepository, times(2)).saveAll(captor.capture());
        List<EmployeeLeaveBalance> result = new ArrayList<>();
        captor.getAllValues().get(call).forEach(result::add);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<LeaveTransaction> savedTransactions() {
        ArgumentCaptor<Iterable<LeaveTransaction>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(leaveTransactionRepository).saveAll(captor.capture());
        List<LeaveTransaction> result = new ArrayList<>();
        captor.getValue().forEach(result::add);
        return result;
    }

    private static <T> T single(Collection<T> items) {
        assertEquals(1, items.size());
        return items.iterator().next();
    }

    private static void assertTxn(LeaveTransaction txn, LeaveTransactionType type, double days, int year,
                                  double before, double after) {
        assertEquals(type, txn.getTransactionType());
        assertEquals(days, txn.getDays());
        assertEquals(year, txn.getYear());
        assertEquals(before, txn.getBalanceBefore());
        assertEquals(after, txn.getBalanceAfter());
    }

    private static Employee employee(String id, boolean deleted) {
        Employee employee = new Employee();
        employee.setEmployeeId(id);
        employee.setDeleted(deleted);
        return employee;
    }

    private static EmployeeLeaveBalance balance(String employeeId, String type, int year, double leaveBalance, double carry) {
        EmployeeLeaveBalance balance = new EmployeeLeaveBalance();
        balance.setEmployeeId(employeeId);
        balance.setLeaveTypeName(type);
        balance.setYear(year);
        balance.setLeaveBalance(leaveBalance);
        balance.setCarryForwardDays(carry);
        balance.setActive(true);
        return balance;
    }

    private static LeaveDisbursalDto type(String name, boolean carryForward, Double cap) {
        LeaveDisbursalDto dto = new LeaveDisbursalDto();
        dto.setName(name);
        dto.setCarryForward(carryForward);
        dto.setMaxCarryForwardDays(cap);
        return dto;
    }
}
