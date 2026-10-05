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
import com.hrms.employee.management.dao.EmployeeWfhBalance;
import com.hrms.employee.management.dao.WFHTransaction;
import com.hrms.employee.management.dto.WFHDisbursalDto;
import com.hrms.employee.management.repository.DisbursalRunRepository;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.EmployeeWfhBalanceRepository;
import com.hrms.employee.management.repository.WFHTransactionRepository;
import com.hrms.employee.management.utility.DisbursalEligibility;
import com.hrms.employee.management.utility.DisbursalFrequency;
import com.hrms.employee.management.utility.TenantContext;
import com.hrms.employee.management.utility.TenantJobRunner;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
@EnableScheduling
public class WFHDisbursalSchedulerService {

    private static final String KIND_WFH = "WFH";

    @Value("${company.service.url}")
    private String companyServiceBaseUrl;

    @Value("${defaultTenant}")
    private String defaultTenant;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeWfhBalanceRepository employeeWfhBalanceRepository;

    @Autowired
    private WFHTransactionRepository wfhTransactionRepository;

    @Autowired
    private DisbursalRunRepository disbursalRunRepository;

    @Autowired
    private TenantJobRunner tenantJobRunner;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Scheduled(cron = "0 0 0 1 * ?")
    public void scheduledMonthlyWFH() {
        tenantJobRunner.forEachTenant("monthly WFH disbursal", tenantId -> disburseMonthlyWFH());
    }

    @Scheduled(cron = "0 0 0 1 1 ?")
    public void scheduledYearlyWFH() {
        tenantJobRunner.forEachTenant("yearly WFH disbursal", tenantId -> disburseYearlyWFH());
    }

    @Scheduled(cron = "0 0 0 1 1,4,7,10 ?")
    public void scheduledQuarterlyWFH() {
        tenantJobRunner.forEachTenant("quarterly WFH disbursal", tenantId -> disburseQuarterlyWFH());
    }

    @Scheduled(cron = "0 0 0 1 1,7 ?")
    public void scheduledHalfYearlyWFH() {
        tenantJobRunner.forEachTenant("half-yearly WFH disbursal", tenantId -> disburseHalfYearlyWFH());
    }

    // The disburse*WFH methods run for the tenant in TenantContext (the request's X-Tenant-Id when
    // called from WFHBalanceController). Re-running for an already disbursed period is a no-op.

    public void disburseMonthlyWFH() {
        disburseWFHBySchedule(DisbursalFrequency.MONTHLY);
    }

    public void disburseYearlyWFH() {
        disburseWFHBySchedule(DisbursalFrequency.YEARLY);
    }

    public void disburseQuarterlyWFH() {
        disburseWFHBySchedule(DisbursalFrequency.QUARTERLY);
    }

    public void disburseHalfYearlyWFH() {
        disburseWFHBySchedule(DisbursalFrequency.HALF_YEARLY);
    }

    private void disburseWFHBySchedule(DisbursalFrequency frequency) {
        String tenantId = currentTenantOrDefault();
        LocalDate today = LocalDate.now();
        int currentYear = today.getYear();
        String periodKey = frequency.periodKey(today);
        LocalDate periodStart = frequency.periodStart(today);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenantId);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<WFHDisbursalDto[]> wfhDisbursals = restTemplate.exchange(
                companyServiceBaseUrl + "/wfh-types/schedule/" + frequency.getScheduleSegment(),
                HttpMethod.GET,
                entity,
                WFHDisbursalDto[].class);

        if (wfhDisbursals.getBody() == null || wfhDisbursals.getBody().length == 0) {
            log.warn("No {} WFH types found for tenant={}. Skipping disbursal.", frequency, tenantId);
            return;
        }

        for (WFHDisbursalDto wfh : wfhDisbursals.getBody()) {
            String wfhTypeName = wfh.getWfhType();
            if (wfhTypeName == null || wfhTypeName.isBlank()) {
                log.error("{} WFH type without a name for tenant={} (wfhId={}). Skipping it.", frequency, tenantId, wfh.getWfhId());
                continue;
            }
            // WFH balances are whole days, so the annual total is spread across periods in whole days.
            int daysToDisburse = frequency.wholeDaysForPeriod(wfh.getTotalDays(), today);
            try {
                // Claim and credit commit or roll back together; see DisbursalRunRepository.claim.
                Boolean disbursed = transactionTemplate.execute(status -> {
                    int claimed = disbursalRunRepository.claim(tenantId, KIND_WFH, wfhTypeName,
                            frequency.name(), periodKey, daysToDisburse);
                    if (claimed == 0) {
                        return false;
                    }
                    disburseWFH(wfhTypeName, daysToDisburse, currentYear, periodStart, frequency.name() + " disbursal " + periodKey);
                    return true;
                });
                if (Boolean.TRUE.equals(disbursed)) {
                    log.info("Disbursed WFH type={} days={} tenant={} period={}", wfhTypeName, daysToDisburse, tenantId, periodKey);
                } else {
                    log.info("Skipping WFH type={} tenant={} period={}: already disbursed", wfhTypeName, tenantId, periodKey);
                }
            } catch (Exception e) {
                log.error("WFH disbursal failed for type={} tenant={} period={}: {}",
                        wfhTypeName, tenantId, periodKey, e.getMessage(), e);
            }
        }
    }

    private String currentTenantOrDefault() {
        String tenantId = TenantContext.getCurrentTenant();
        return tenantId == null || tenantId.isBlank() ? defaultTenant : tenantId;
    }

    private void disburseWFH(String wfhTypeName, int daysToDisburse, int year, LocalDate periodStart, String reason) {
        if (daysToDisburse == 0) {
            log.info("Nothing to credit for WFH type={} in the period starting {}", wfhTypeName, periodStart);
            return;
        }

        // Deleted and inactive employees don't accrue; joiners in this period were prorated at init.
        List<Employee> employees = employeeRepository.findAll().stream()
                .filter(employee -> DisbursalEligibility.accruesForPeriod(employee, periodStart))
                .collect(Collectors.toList());

        // This year's open balance per employee. If an employee has duplicate rows, credit the oldest.
        Map<String, EmployeeWfhBalance> balanceByEmployee = employeeWfhBalanceRepository
                .findByWfhTypeNameAndYearAndIsActiveTrue(wfhTypeName, year).stream()
                .collect(Collectors.toMap(
                        EmployeeWfhBalance::getEmployeeId,
                        balance -> balance,
                        (first, second) -> {
                            log.warn("Duplicate WFH balance rows for employeeId={} type={} year={} (ids {} and {})",
                                    first.getEmployeeId(), wfhTypeName, year, first.getId(), second.getId());
                            return first.getId() <= second.getId() ? first : second;
                        }));

        List<EmployeeWfhBalance> updatedBalances = new ArrayList<>();
        List<WFHTransaction> transactions = new ArrayList<>();
        for (Employee employee : employees) {
            EmployeeWfhBalance balance = balanceByEmployee.get(employee.getEmployeeId());
            if (balance == null) {
                balance = new EmployeeWfhBalance();
                balance.setEmployeeId(employee.getEmployeeId());
                balance.setWfhTypeName(wfhTypeName);
                balance.setWfhBalance(0);
                balance.setYear(year);
                balance.setActive(true);
            }
            int balanceBefore = balance.getWfhBalance() == null ? 0 : balance.getWfhBalance();
            balance.setWfhBalance(balanceBefore + daysToDisburse);
            updatedBalances.add(balance);

            WFHTransaction transaction = new WFHTransaction();
            transaction.setEmployeeId(employee.getEmployeeId());
            transaction.setWfhTypeName(wfhTypeName);
            transaction.setDays(daysToDisburse);
            transaction.setTransactionType("CREDIT");
            transaction.setYear(year);
            transaction.setBalanceBefore((double) balanceBefore + balance.getCarryForwardDays());
            transaction.setBalanceAfter((double) balance.getWfhBalance() + balance.getCarryForwardDays());
            transaction.setReason(reason);
            transactions.add(transaction);
        }

        employeeWfhBalanceRepository.saveAll(updatedBalances);
        wfhTransactionRepository.saveAll(transactions);
        log.info("Credited {} WFH day(s) to {} employee(s) for WFH type={} year={}",
                daysToDisburse, employees.size(), wfhTypeName, year);
    }

}
