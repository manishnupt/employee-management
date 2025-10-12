package com.hrms.employee.management.service;

import java.util.Arrays;
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
import org.springframework.web.client.RestTemplate;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeWfhBalance;
import com.hrms.employee.management.dao.WFHTransaction;
import com.hrms.employee.management.dto.WFHDisbursalDto;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.EmployeeWfhBalanceRepository;
import com.hrms.employee.management.repository.WFHTransactionRepository;
import com.hrms.employee.management.utility.TenantContext;

import jakarta.transaction.Transactional;
import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
@EnableScheduling
public class WFHDisbursalSchedulerService {

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

    @Scheduled(cron = "0 0 0 1 * ?")
    public void disburseMonthlyWFH() {
        disburseWFHBySchedule("monthly", 12);
    }

    @Scheduled(cron = "0 0 0 1 1 ?")
    public void disburseYearlyWFH() {
        disburseWFHBySchedule("yearly", 1);
    }

    @Scheduled(cron = "0 0 0 1 1,4,7,10 ?")
    public void disburseQuarterlyWFH() {
        disburseWFHBySchedule("quarterly", 4);
    }

    @Scheduled(cron = "0 0 0 1 1,7 ?")
    public void disburseHalfYearlyWFH() {
        disburseWFHBySchedule("half_yearly", 2);
    }

    private void disburseWFHBySchedule(String scheduleType, int divisor) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<WFHDisbursalDto[]> wfhDisbursals = restTemplate.exchange(
                companyServiceBaseUrl + "/wfh-types/schedule/" + scheduleType,
                HttpMethod.GET,
                entity,
                WFHDisbursalDto[].class);

    
        List<WFHDisbursalDto> wfhDisbursal = Arrays.asList(wfhDisbursals.getBody());
        for (WFHDisbursalDto wfh : wfhDisbursal) {
            disburseWFH(divisor, wfh);
        }
    }
    @Transactional
    public void disburseWFH(int divisor, WFHDisbursalDto wfh){
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