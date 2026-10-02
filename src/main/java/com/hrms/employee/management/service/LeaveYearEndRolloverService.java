package com.hrms.employee.management.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
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
import com.hrms.employee.management.utility.DisbursalFrequency;
import com.hrms.employee.management.utility.EmployeeLeaveKey;
import com.hrms.employee.management.utility.LeaveTransactionType;

import lombok.extern.log4j.Log4j2;

/**
 * Closes leave balances of earlier years and opens the new year's balances, carrying forward or
 * lapsing what is left. Runs once per tenant per year, guarded by a disbursal_run claim
 * (kind LEAVE_ROLLOVER, period = target year) taken in the same transaction as the rollover.
 *
 * Ledger entries per closed balance (all days positive, direction given by the type):
 *   LAPSE             year = closing year, days forfeited
 *   CARRY_FORWARD_OUT year = closing year, days moved out
 *   CARRY_FORWARD     year = new year,     days moved in
 */
@Service
@Log4j2
public class LeaveYearEndRolloverService {

    static final String KIND_ROLLOVER = "LEAVE_ROLLOVER";
    private static final String ALL_TYPES = "*";

    @Value("${company.service.url}")
    private String companyServiceBaseUrl;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeLeaveBalanceRepository leaveBalanceRepository;

    @Autowired
    private LeaveTransactionRepository leaveTransactionRepository;

    @Autowired
    private DisbursalRunRepository disbursalRunRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * Rolls balances from years before {@code targetYear} into {@code targetYear} for the tenant in
     * TenantContext, unless that has already been done. Throws if it cannot complete, so callers
     * must not credit the new year until this succeeds.
     */
    public void rolloverIfNeeded(String tenantId, int targetYear) {
        String periodKey = String.valueOf(targetYear);
        if (disbursalRunRepository.existsByTenantIdAndKindAndTypeNameAndPeriodKey(tenantId, KIND_ROLLOVER, ALL_TYPES, periodKey)) {
            return;
        }

        // Fetched before the transaction so no DB lock is held during the HTTP calls.
        Map<String, LeaveDisbursalDto> leaveTypes = fetchLeaveTypes(tenantId);

        transactionTemplate.executeWithoutResult(status -> {
            int claimed = disbursalRunRepository.claim(tenantId, KIND_ROLLOVER, ALL_TYPES,
                    DisbursalFrequency.YEARLY.name(), periodKey, 0);
            if (claimed == 0) {
                log.info("Leave rollover to year={} already done for tenant={}", targetYear, tenantId);
                return;
            }
            rollover(tenantId, targetYear, leaveTypes);
        });
    }

    private void rollover(String tenantId, int targetYear, Map<String, LeaveDisbursalDto> leaveTypes) {
        List<EmployeeLeaveBalance> closingBalances = leaveBalanceRepository.findByYearLessThanAndIsActiveTrueOrderByYearAsc(targetYear);
        if (closingBalances.isEmpty()) {
            log.info("Leave rollover to year={} tenant={}: no balances from earlier years", targetYear, tenantId);
            return;
        }

        Set<String> activeEmployeeIds = employeeRepository.findAll().stream()
                .filter(employee -> !employee.isDeleted())
                .map(Employee::getEmployeeId)
                .collect(Collectors.toSet());

        // Fails on duplicate rows for the same employee/type/year rather than silently picking one.
        Map<EmployeeLeaveKey, EmployeeLeaveBalance> openingBalances = leaveBalanceRepository.findAllByYearAndIsActiveTrue(targetYear).stream()
                .collect(Collectors.toMap(
                        balance -> new EmployeeLeaveKey(balance.getEmployeeId(), balance.getLeaveTypeName()),
                        balance -> balance,
                        (a, b) -> { throw new IllegalStateException("Duplicate active leave balance for employeeId="
                                + a.getEmployeeId() + " leaveType=" + a.getLeaveTypeName() + " year=" + targetYear); },
                        LinkedHashMap::new));

        List<LeaveTransaction> transactions = new ArrayList<>();
        double totalCarried = 0;
        double totalLapsed = 0;

        for (EmployeeLeaveBalance closing : closingBalances) {
            String employeeId = closing.getEmployeeId();
            String leaveTypeName = closing.getLeaveTypeName();
            LeaveDisbursalDto leaveType = leaveTypes.get(leaveTypeName);
            boolean employeeActive = activeEmployeeIds.contains(employeeId);

            double remaining = closing.getAvailableDays();
            double eligible = Math.max(remaining, 0);
            double carry = 0;
            String lapseReason;
            if (!employeeActive) {
                lapseReason = "Employee deleted or not found";
            } else if (leaveType == null) {
                lapseReason = "Leave type discontinued";
            } else if (!leaveType.isCarryForward()) {
                lapseReason = "Leave type does not allow carry forward";
            } else {
                Double cap = leaveType.getMaxCarryForwardDays();
                carry = cap == null ? eligible : Math.min(eligible, Math.max(cap, 0));
                lapseReason = "Exceeds carry-forward cap of " + cap + " day(s)";
            }
            carry = round2(carry);
            double lapse = round2(eligible - carry);

            if (remaining < 0) {
                log.warn("Leave rollover: employeeId={} leaveType={} year={} closes negative ({}); deficit not carried",
                        employeeId, leaveTypeName, closing.getYear(), remaining);
            }

            double closingBalance = remaining;
            if (lapse > 0) {
                transactions.add(transaction(employeeId, leaveTypeName, LeaveTransactionType.LAPSE, lapse,
                        closing.getYear(), closingBalance, closingBalance - lapse, lapseReason));
                closingBalance -= lapse;
            }
            if (carry > 0) {
                transactions.add(transaction(employeeId, leaveTypeName, LeaveTransactionType.CARRY_FORWARD_OUT, carry,
                        closing.getYear(), closingBalance, closingBalance - carry, "Carried forward to " + targetYear));
            }
            closing.setActive(false);

            if (employeeActive && leaveType != null) {
                EmployeeLeaveBalance opening = openingBalances.computeIfAbsent(
                        new EmployeeLeaveKey(employeeId, leaveTypeName),
                        key -> newBalance(employeeId, leaveTypeName, targetYear));
                if (carry > 0) {
                    double before = opening.getAvailableDays();
                    opening.setCarryForwardDays(opening.getCarryForwardDays() + carry);
                    transactions.add(transaction(employeeId, leaveTypeName, LeaveTransactionType.CARRY_FORWARD, carry,
                            targetYear, before, before + carry, "Carried forward from " + closing.getYear()));
                }
            }
            totalCarried += carry;
            totalLapsed += lapse;
        }

        leaveBalanceRepository.saveAll(closingBalances);
        leaveBalanceRepository.saveAll(openingBalances.values());
        leaveTransactionRepository.saveAll(transactions);
        log.info("Leave rollover to year={} tenant={}: closed={} opened/updated={} carried={} lapsed={} ledgerEntries={}",
                targetYear, tenantId, closingBalances.size(), openingBalances.size(),
                round2(totalCarried), round2(totalLapsed), transactions.size());
    }

    /**
     * All leave types across every disbursal frequency, by name. Refuses an empty result so a
     * company-service outage cannot be mistaken for "every type discontinued" and lapse everything.
     */
    private Map<String, LeaveDisbursalDto> fetchLeaveTypes(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenantId);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        Map<String, LeaveDisbursalDto> leaveTypes = new LinkedHashMap<>();
        for (DisbursalFrequency frequency : DisbursalFrequency.values()) {
            String url = companyServiceBaseUrl + "/leave-types/schedule/" + frequency.getScheduleSegment();
            ResponseEntity<LeaveDisbursalDto[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, LeaveDisbursalDto[].class);
            if (response.getBody() != null) {
                for (LeaveDisbursalDto leaveType : response.getBody()) {
                    leaveTypes.putIfAbsent(leaveType.getName(), leaveType);
                }
            }
        }
        if (leaveTypes.isEmpty()) {
            throw new IllegalStateException("No leave types returned by company service for tenant=" + tenantId
                    + "; refusing to roll over and lapse all balances");
        }
        return leaveTypes;
    }

    private EmployeeLeaveBalance newBalance(String employeeId, String leaveTypeName, int year) {
        EmployeeLeaveBalance balance = new EmployeeLeaveBalance();
        balance.setEmployeeId(employeeId);
        balance.setLeaveTypeName(leaveTypeName);
        balance.setLeaveBalance(0);
        balance.setCarryForwardDays(0);
        balance.setYear(year);
        balance.setActive(true);
        return balance;
    }

    private LeaveTransaction transaction(String employeeId, String leaveTypeName, LeaveTransactionType type, double days,
                                         int year, double balanceBefore, double balanceAfter, String reason) {
        LeaveTransaction transaction = new LeaveTransaction();
        transaction.setEmployeeId(employeeId);
        transaction.setLeaveTypeName(leaveTypeName);
        transaction.setTransactionType(type);
        transaction.setDays(days);
        transaction.setYear(year);
        transaction.setBalanceBefore(round2(balanceBefore));
        transaction.setBalanceAfter(round2(balanceAfter));
        transaction.setReason(reason);
        return transaction;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
