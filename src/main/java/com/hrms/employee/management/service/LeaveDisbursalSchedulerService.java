package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
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
import com.hrms.employee.management.utility.DisbursalEligibility;
import com.hrms.employee.management.utility.DisbursalFrequency;
import com.hrms.employee.management.utility.EmployeeLeaveKey;
import com.hrms.employee.management.utility.LeaveTransactionType;
import com.hrms.employee.management.utility.TenantContext;
import com.hrms.employee.management.utility.TenantJobRunner;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
@EnableScheduling
public class LeaveDisbursalSchedulerService {

    private static final String KIND_LEAVE = "LEAVE";

    @Value("${company.service.url}")
    private String companyServiceBaseUrl;

    @Value("${defaultTenant}")
    private String defaultTenant;

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
    private TenantJobRunner tenantJobRunner;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private LeaveYearEndRolloverService leaveYearEndRolloverService;

    @Scheduled(cron = "0 0 0 1 * ?")
    public void scheduledMonthlyLeave() {
        tenantJobRunner.forEachTenant("monthly leave disbursal", tenantId -> disburseMonthlyLeave());
    }

    @Scheduled(cron = "0 0 0 1 1 ?")
    public void scheduledYearlyLeave() {
        tenantJobRunner.forEachTenant("yearly leave disbursal", tenantId -> disburseYearlyLeave());
    }

    @Scheduled(cron = "0 0 0 1 1,4,7,10 ?")
    public void scheduledQuarterlyLeave() {
        tenantJobRunner.forEachTenant("quarterly leave disbursal", tenantId -> disburseQuarterlyLeave());
    }

    @Scheduled(cron = "0 0 0 1 1,7 ?")
    public void scheduledHalfYearlyLeave() {
        tenantJobRunner.forEachTenant("half-yearly leave disbursal", tenantId -> disburseHalfYearlyLeave());
    }

    // The disburse*Leave methods run for the tenant in TenantContext (the request's X-Tenant-Id when
    // called from LeaveBalanceController). Re-running for an already disbursed period is a no-op.

    public void disburseMonthlyLeave() {
        disburseLeaveBySchedule(DisbursalFrequency.MONTHLY);
    }

    public void disburseYearlyLeave() {
        disburseLeaveBySchedule(DisbursalFrequency.YEARLY);
    }

    public void disburseQuarterlyLeave() {
        disburseLeaveBySchedule(DisbursalFrequency.QUARTERLY);
    }

    public void disburseHalfYearlyLeave() {
        disburseLeaveBySchedule(DisbursalFrequency.HALF_YEARLY);
    }

    /** Rolls earlier years' leave balances into the current year, if not already done. */
    public void rolloverLeaveYear() {
        leaveYearEndRolloverService.rolloverIfNeeded(currentTenantOrDefault(), LocalDate.now().getYear());
    }

    private void disburseLeaveBySchedule(DisbursalFrequency frequency) {
        String tenantId = currentTenantOrDefault();
        LocalDate today = LocalDate.now();
        int currentYear = today.getYear();
        String periodKey = frequency.periodKey(today);
        LocalDate periodStart = frequency.periodStart(today);
        log.debug("disburseLeaveBySchedule triggered. tenant={} frequency={} period={}", tenantId, frequency, periodKey);

        // Close last year before crediting this year. Whichever 1 Jan job runs first does the
        // rollover; the rest find it done. If it fails, nothing is credited for this tenant.
        leaveYearEndRolloverService.rolloverIfNeeded(tenantId, currentYear);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenantId);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        String url = companyServiceBaseUrl + "/leave-types/schedule/" + frequency.getScheduleSegment();
        log.debug("Calling {} for {} leave-types schedule", url, frequency);
        ResponseEntity<LeaveDisbursalDto[]> leaveDisbursals = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                LeaveDisbursalDto[].class);
        log.debug("{} schedule response status={} bodyLength={}", frequency, leaveDisbursals.getStatusCode(),
                leaveDisbursals.getBody() == null ? null : leaveDisbursals.getBody().length);

        if (leaveDisbursals.getBody() == null || leaveDisbursals.getBody().length == 0) {
            log.warn("No {} leave types found for tenant={}. Skipping disbursal.", frequency, tenantId);
            return;
        }

        for (LeaveDisbursalDto leave : leaveDisbursals.getBody()) {
            double daysToDisburse = Math.round(leave.getTotalDays() / (double) frequency.getPeriodsPerYear() * 100.0) / 100.0;
            try {
                // Claim and credit commit or roll back together, so a failed run can be retried
                // and a concurrent replica waiting on the claim skips once this one commits.
                Boolean disbursed = transactionTemplate.execute(status -> {
                    int claimed = disbursalRunRepository.claim(tenantId, KIND_LEAVE, leave.getName(),
                            frequency.name(), periodKey, daysToDisburse);
                    if (claimed == 0) {
                        return false;
                    }
                    disburseLeave(leave.getName(), daysToDisburse, currentYear, periodStart, frequency.name() + " disbursal " + periodKey);
                    return true;
                });
                if (Boolean.TRUE.equals(disbursed)) {
                    log.info("Disbursed leaveName={} days={} tenant={} period={}", leave.getName(), daysToDisburse, tenantId, periodKey);
                } else {
                    log.info("Skipping leaveName={} tenant={} period={}: already disbursed", leave.getName(), tenantId, periodKey);
                }
            } catch (Exception e) {
                log.error("Leave disbursal failed for leaveName={} tenant={} period={}: {}",
                        leave.getName(), tenantId, periodKey, e.getMessage(), e);
            }
        }
        log.debug("disburseLeaveBySchedule completed. tenant={} frequency={}", tenantId, frequency);
    }

    private String currentTenantOrDefault() {
        String tenantId = TenantContext.getCurrentTenant();
        return tenantId == null || tenantId.isBlank() ? defaultTenant : tenantId;
    }

    private void disburseLeave(String leaveName, double daysToDisburse, int year, LocalDate periodStart, String reason) {
        log.debug("disburseLeave started - leaveName={} daysToDisburse={} year={}", leaveName, daysToDisburse, year);

        // Deleted and inactive employees don't accrue; joiners in this period were prorated at init.
        List<Employee> employees = employeeRepository.findAll().stream()
                .filter(employee -> DisbursalEligibility.accruesForPeriod(employee, periodStart))
                .collect(Collectors.toList());
        log.debug("Fetched {} eligible employee(s) for disbursal of leaveName={} periodStart={}",
                employees.size(), leaveName, periodStart);

        // Only this year's open balances; earlier years are closed by the year-end rollover.
        Map<EmployeeLeaveKey, EmployeeLeaveBalance> leaveBalanceMap = leaveBalanceRepository
                .findByLeaveTypeNameAndYearAndIsActiveTrue(leaveName, year).stream()
                .collect(Collectors.toMap(
                        balance -> new EmployeeLeaveKey(balance.getEmployeeId(), leaveName),
                        balance -> balance));
        log.debug("Existing leave balance entries for leaveName={} year={}: {}", leaveName, year, leaveBalanceMap.size());

        List<LeaveTransaction> leaveTransactions = new ArrayList<>();
        List<EmployeeLeaveBalance> updatedLeaveBalances = new ArrayList<>();
        for (Employee employee : employees) {
            EmployeeLeaveKey employeeLeaveKey = new EmployeeLeaveKey(employee.getEmployeeId(), leaveName);
            EmployeeLeaveBalance balance = leaveBalanceMap.get(employeeLeaveKey);

            if (balance == null) {
                log.debug("No existing balance for employeeId={} leaveName={}. Creating new balance for year={}",
                        employee.getEmployeeId(), leaveName, year);
                balance = new EmployeeLeaveBalance();
                balance.setEmployeeId(employee.getEmployeeId());
                balance.setLeaveTypeName(leaveName);
                balance.setLeaveBalance(0);
                balance.setYear(year);
                balance.setActive(true);
            }

            double availableBefore = balance.getAvailableDays();
            balance.setLeaveBalance(balance.getLeaveBalance() + daysToDisburse);
            log.debug("employeeId={} leaveName={} availableBefore={} daysToDisburse={} availableAfter={}",
                    employee.getEmployeeId(), leaveName, availableBefore, daysToDisburse, balance.getAvailableDays());
            updatedLeaveBalances.add(balance);

            LeaveTransaction transaction = new LeaveTransaction();
            transaction.setEmployeeId(employee.getEmployeeId());
            transaction.setLeaveTypeName(leaveName);
            transaction.setTransactionType(LeaveTransactionType.CREDIT);
            transaction.setDays(daysToDisburse);
            transaction.setYear(year);
            transaction.setBalanceBefore(availableBefore);
            transaction.setBalanceAfter(balance.getAvailableDays());
            transaction.setReason(reason);
            leaveTransactions.add(transaction);
        }

        log.debug("Saving {} updated leave balance(s) and {} leave transaction(s) for leaveName={}",
                updatedLeaveBalances.size(), leaveTransactions.size(), leaveName);
        leaveBalanceRepository.saveAll(updatedLeaveBalances);
        leaveTransactionRepository.saveAll(leaveTransactions);
        log.debug("disburseLeave completed - leaveName={}", leaveName);
    }

}