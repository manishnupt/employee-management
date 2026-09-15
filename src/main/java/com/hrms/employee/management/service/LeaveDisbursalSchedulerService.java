package com.hrms.employee.management.service;

import java.util.ArrayList;
import java.util.Arrays;
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
import org.springframework.web.client.RestTemplate;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import com.hrms.employee.management.dao.LeaveTransaction;
import com.hrms.employee.management.dto.LeaveDisbursalDto;
import com.hrms.employee.management.repository.EmployeeLeaveBalanceRepository;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTransactionRepository;
import com.hrms.employee.management.utility.EmployeeLeaveKey;
import com.hrms.employee.management.utility.LeaveTransactionType;
import com.hrms.employee.management.utility.TenantContext;

import jakarta.transaction.Transactional;
import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
@EnableScheduling
public class LeaveDisbursalSchedulerService {

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

    @Scheduled(cron = "0 0 0 1 * ?")
    public void disburseMonthlyLeave() {
        log.debug("disburseMonthlyLeave triggered. tenant={}", TenantContext.getCurrentTenant());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());
        HttpEntity<String> entity = new HttpEntity<>(headers);

        log.debug("Calling {} for monthly leave-types schedule", companyServiceBaseUrl + "/leave-types/schedule/monthly");
        ResponseEntity<LeaveDisbursalDto[]> leaveDisbursals = restTemplate.exchange(
                companyServiceBaseUrl + "/leave-types/schedule/monthly",
                HttpMethod.GET,
                entity,
                LeaveDisbursalDto[].class);
        log.debug("Monthly schedule response status={} bodyLength={}", leaveDisbursals.getStatusCode(),
                leaveDisbursals.getBody() == null ? null : leaveDisbursals.getBody().length);

        if (leaveDisbursals.getBody().length == 0) {
            log.warn("No quarterly leave types found. Skipping disbursal.");
            return;
        }
        List<LeaveDisbursalDto> leaveDisbursal = Arrays.asList(leaveDisbursals.getBody());
        log.debug("Disbursing monthly leave for {} leave type(s): {}", leaveDisbursal.size(),
                leaveDisbursal.stream().map(LeaveDisbursalDto::getName).collect(Collectors.toList()));
        for (LeaveDisbursalDto leave : leaveDisbursal) {
            log.debug("Monthly disbursal - leaveName={} totalDays={} divisor=12", leave.getName(), leave.getTotalDays());
            disburseLeave(leave.getName(), 12, leave);
        }
        log.debug("disburseMonthlyLeave completed.");

    }

    @Scheduled(cron = "0 0 0 1 1 ?")
    public void disburseYearlyLeave() {
        log.debug("disburseYearlyLeave triggered. tenant={}", TenantContext.getCurrentTenant());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());
        HttpEntity<String> entity = new HttpEntity<>(headers);

        log.debug("Calling {} for yearly leave-types schedule", companyServiceBaseUrl + "/leave-types/schedule/yearly");
        ResponseEntity<LeaveDisbursalDto[]> leaveDisbursals = restTemplate.exchange(
                companyServiceBaseUrl + "/leave-types/schedule/yearly",
                HttpMethod.GET,
                entity,
                LeaveDisbursalDto[].class);
        log.debug("Yearly schedule response status={} bodyLength={}", leaveDisbursals.getStatusCode(),
                leaveDisbursals.getBody() == null ? null : leaveDisbursals.getBody().length);

        if (leaveDisbursals.getBody().length == 0) {
            log.warn("No yearly leave types found. Skipping disbursal.");
            return;
        }

        List<LeaveDisbursalDto> leaveDisbursal = Arrays.asList(leaveDisbursals.getBody());
        log.debug("Disbursing yearly leave for {} leave type(s): {}", leaveDisbursal.size(),
                leaveDisbursal.stream().map(LeaveDisbursalDto::getName).collect(Collectors.toList()));
        for (LeaveDisbursalDto leave : leaveDisbursal) {
            log.debug("Yearly disbursal - leaveName={} totalDays={} divisor=1", leave.getName(), leave.getTotalDays());
            disburseLeave(leave.getName(), 1, leave);
        }
        log.debug("disburseYearlyLeave completed.");

    }

    @Scheduled(cron = "0 0 0 1 1,4,7,10 ?")
    public void disburseQuarterlyLeave() {
        log.debug("disburseQuarterlyLeave triggered. tenant={}", TenantContext.getCurrentTenant());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());
        HttpEntity<String> entity = new HttpEntity<>(headers);

        log.debug("Calling {} for quarterly leave-types schedule", companyServiceBaseUrl + "/leave-types/schedule/quarterly");
        ResponseEntity<LeaveDisbursalDto[]> leaveDisbursals = restTemplate.exchange(
                companyServiceBaseUrl + "/leave-types/schedule/quarterly",
                HttpMethod.GET,
                entity,
                LeaveDisbursalDto[].class);
        log.debug("Quarterly schedule response status={} bodyLength={}", leaveDisbursals.getStatusCode(),
                leaveDisbursals.getBody() == null ? null : leaveDisbursals.getBody().length);
        if (leaveDisbursals.getBody().length == 0) {
            log.warn("No quarterly leave types found. Skipping disbursal.");
            return;
        }
        List<LeaveDisbursalDto> leaveDisbursal = Arrays.asList(leaveDisbursals.getBody());
        log.debug("Disbursing quarterly leave for {} leave type(s): {}", leaveDisbursal.size(),
                leaveDisbursal.stream().map(LeaveDisbursalDto::getName).collect(Collectors.toList()));
        for (LeaveDisbursalDto leave : leaveDisbursal) {
            log.debug("Quarterly disbursal - leaveName={} totalDays={} divisor=4", leave.getName(), leave.getTotalDays());
            disburseLeave(leave.getName(), 4, leave);
        }
        log.debug("disburseQuarterlyLeave completed.");

    }

    @Scheduled(cron = "0 0 0 1 1,7 ?")
    public void disburseHalfYearlyLeave() {
        log.debug("disburseHalfYearlyLeave triggered. tenant={}", TenantContext.getCurrentTenant());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());
        HttpEntity<String> entity = new HttpEntity<>(headers);

        log.debug("Calling {} for half-yearly leave-types schedule", companyServiceBaseUrl + "/leave-types/schedule/half_yearly");
        ResponseEntity<LeaveDisbursalDto[]> leaveDisbursals = restTemplate.exchange(
                companyServiceBaseUrl + "/leave-types/schedule/half_yearly",
                HttpMethod.GET,
                entity,
                LeaveDisbursalDto[].class);
        log.debug("Half-yearly schedule response status={} bodyLength={}", leaveDisbursals.getStatusCode(),
                leaveDisbursals.getBody() == null ? null : leaveDisbursals.getBody().length);

        if (leaveDisbursals.getBody().length == 0) {
            log.warn("No half-yearly leave types found. Skipping disbursal.");
            return;
        }
        List<LeaveDisbursalDto> leaveDisbursal = Arrays.asList(leaveDisbursals.getBody());
        log.debug("Disbursing half-yearly leave for {} leave type(s): {}", leaveDisbursal.size(),
                leaveDisbursal.stream().map(LeaveDisbursalDto::getName).collect(Collectors.toList()));

        for (LeaveDisbursalDto leave : leaveDisbursal) {
            log.debug("Half-yearly disbursal - leaveName={} totalDays={} divisor=2", leave.getName(), leave.getTotalDays());
            disburseLeave(leave.getName(), 2, leave);
        }
        log.debug("disburseHalfYearlyLeave completed.");

    }

    @Transactional
    public void disburseLeave(String leaveName, int divisor, LeaveDisbursalDto leaveDisbursal) {
        log.debug("disburseLeave started - leaveName={} divisor={} totalDays={}", leaveName, divisor,
                leaveDisbursal.getTotalDays());

        List<Employee> employees = employeeRepository.findAll();
        log.debug("Fetched {} employee(s) for disbursal of leaveName={}", employees.size(), leaveName);

        double daysToDisburse = leaveDisbursal.getTotalDays() / (double) divisor;
        daysToDisburse = Math.round(daysToDisburse * 100.0) / 100.0;
        log.debug("Computed daysToDisburse={} for leaveName={} (totalDays={} / divisor={})", daysToDisburse,
                leaveName, leaveDisbursal.getTotalDays(), divisor);

        List<EmployeeLeaveBalance> employeeLeaveBalances = leaveBalanceRepository.findAll();
        log.debug("Fetched {} total EmployeeLeaveBalance row(s) before filtering by leaveName={}",
                employeeLeaveBalances.size(), leaveName);

        Map<EmployeeLeaveKey, EmployeeLeaveBalance> leaveBalanceMap = employeeLeaveBalances.stream()
                .filter(e -> e.getLeaveTypeName().equals(leaveName))
                .collect(Collectors.toMap(
                        emp -> new EmployeeLeaveKey(emp.getEmployeeId(), leaveName),
                        emp -> emp));
        log.debug("Existing leave balance entries for leaveName={}: {}", leaveName, leaveBalanceMap.size());

        List<LeaveTransaction> leaveTransactions = new ArrayList<>();
        List<EmployeeLeaveBalance> updatedLeaveBalances = new ArrayList<>();
        int currentYear = java.time.Year.now().getValue();
        for (Employee employee : employees) {
            EmployeeLeaveKey employeeLeaveKey = new EmployeeLeaveKey(employee.getEmployeeId(), leaveName);
            EmployeeLeaveBalance balance = leaveBalanceMap.get(employeeLeaveKey);

            if (balance == null) {
                log.debug("No existing balance for employeeId={} leaveName={}. Creating new balance for year={}",
                        employee.getEmployeeId(), leaveName, currentYear);
                balance = new EmployeeLeaveBalance();
                balance.setEmployeeId(employee.getEmployeeId());
                balance.setLeaveTypeName(leaveName);
                balance.setLeaveBalance(0);
                balance.setYear(currentYear);
            }

            double balanceBefore = balance.getLeaveBalance();
            balance.setLeaveBalance(balance.getLeaveBalance() + daysToDisburse);
            log.debug("employeeId={} leaveName={} balanceBefore={} daysToDisburse={} balanceAfter={}",
                    employee.getEmployeeId(), leaveName, balanceBefore, daysToDisburse, balance.getLeaveBalance());
            updatedLeaveBalances.add(balance);

            LeaveTransaction transaction = new LeaveTransaction();
            transaction.setEmployeeId(employee.getEmployeeId());
            transaction.setLeaveTypeName(leaveName);
            transaction.setTransactionType(LeaveTransactionType.CREDIT);
            transaction.setDays(daysToDisburse);
            leaveTransactions.add(transaction);
        }

        log.debug("Saving {} updated leave balance(s) and {} leave transaction(s) for leaveName={}",
                updatedLeaveBalances.size(), leaveTransactions.size(), leaveName);
        leaveBalanceRepository.saveAll(updatedLeaveBalances);
        leaveTransactionRepository.saveAll(leaveTransactions);
        log.debug("disburseLeave completed - leaveName={} divisor={}", leaveName, divisor);
    }

}