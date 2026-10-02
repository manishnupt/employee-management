package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.util.List;
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
    public void disburseMonthlyWFH() {
        tenantJobRunner.forEachTenant("monthly WFH disbursal", tenantId -> disburseWFHBySchedule(DisbursalFrequency.MONTHLY));
    }

    @Scheduled(cron = "0 0 0 1 1 ?")
    public void disburseYearlyWFH() {
        tenantJobRunner.forEachTenant("yearly WFH disbursal", tenantId -> disburseWFHBySchedule(DisbursalFrequency.YEARLY));
    }

    @Scheduled(cron = "0 0 0 1 1,4,7,10 ?")
    public void disburseQuarterlyWFH() {
        tenantJobRunner.forEachTenant("quarterly WFH disbursal", tenantId -> disburseWFHBySchedule(DisbursalFrequency.QUARTERLY));
    }

    @Scheduled(cron = "0 0 0 1 1,7 ?")
    public void disburseHalfYearlyWFH() {
        tenantJobRunner.forEachTenant("half-yearly WFH disbursal", tenantId -> disburseWFHBySchedule(DisbursalFrequency.HALF_YEARLY));
    }

    private void disburseWFHBySchedule(DisbursalFrequency frequency) {
        String tenantId = TenantContext.getCurrentTenant();
        String periodKey = frequency.periodKey(LocalDate.now());
        int divisor = frequency.getPeriodsPerYear();

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
            try {
                // Claim and credit commit or roll back together; see DisbursalRunRepository.claim.
                Boolean disbursed = transactionTemplate.execute(status -> {
                    int claimed = disbursalRunRepository.claim(tenantId, KIND_WFH, wfh.getWfhType(),
                            frequency.name(), periodKey, Math.round(wfh.getTotalDays() / divisor));
                    if (claimed == 0) {
                        return false;
                    }
                    disburseWFH(divisor, wfh);
                    return true;
                });
                if (!Boolean.TRUE.equals(disbursed)) {
                    log.info("Skipping WFH type={} tenant={} period={}: already disbursed", wfh.getWfhType(), tenantId, periodKey);
                }
            } catch (Exception e) {
                log.error("WFH disbursal failed for type={} tenant={} period={}: {}",
                        wfh.getWfhType(), tenantId, periodKey, e.getMessage(), e);
            }
        }
    }

    private void disburseWFH(int divisor, WFHDisbursalDto wfh){
        List<Employee> employees = employeeRepository.findAll();

        List<EmployeeWfhBalance> wfhBalances = employees.stream().map(emp -> {
            EmployeeWfhBalance balance = new EmployeeWfhBalance();
            balance.setEmployeeId(emp.getEmployeeId());
            balance.setWfhTypeName(wfh.getWfhType());
            balance.setWfhBalance((int) Math.round(wfh.getTotalDays() / divisor));
            return balance;
        }).collect(Collectors.toList());

        // Save all balances in one go
        employeeWfhBalanceRepository.saveAll(wfhBalances);

        log.info("Disbursed {} WFH to {} employees for WFH Type: {}", (int) Math.round(wfh.getTotalDays() / divisor), employees.size(), wfh.getWfhType());

        // Log WFH transaction
        List<WFHTransaction> transactions = employees.stream().map(emp -> {
            WFHTransaction transaction = new WFHTransaction();
            transaction.setEmployeeId(emp.getEmployeeId());
            transaction.setWfhTypeName(wfh.getWfhType());
            transaction.setDays((int) Math.round(wfh.getTotalDays() / divisor));
            transaction.setTransactionType("CREDIT");
            return transaction;
        }).collect(Collectors.toList());

        wfhTransactionRepository.saveAll(transactions);
        log.info("Logged WFH transactions for {} employees for WFH Type: {}", employees.size(), wfh.getWfhType());
    }
    
}