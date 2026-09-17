package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeWfhBalance;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.dao.WFHTransaction;
import com.hrms.employee.management.dto.WfhBalanceDto;
import com.hrms.employee.management.dto.WfhType;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.EmployeeWfhBalanceRepository;
import com.hrms.employee.management.repository.EmployeeWfhRepository;
import com.hrms.employee.management.repository.WFHTrackerRepository;
import com.hrms.employee.management.repository.WFHTransactionRepository;
import com.hrms.employee.management.utility.ProrataWfhCalculator;
import com.hrms.employee.management.utility.TenantContext;

import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
public class WfhBalanceServiceImpl implements WfhBalanceService {

    @Autowired
    private EmployeeWfhRepository employeeWfhRepository;

    @Autowired
    private EmployeeWfhBalanceRepository employeeWfhBalanceRepository;

    @Autowired
    private WFHTrackerRepository wfhTrackerRepository;

    @Autowired
    private WFHTransactionRepository wfhTransactionRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${company.service.base.url}")
    private String companyServiceBaseUrl;

    @Override
    public void deductWfhBalance(String employeeId, Long wfhTrackerId) {
        log.info("Deducting WFH balance for employeeId: {}, wfhTrackerId: {}", employeeId, wfhTrackerId);

        EmployeeWfhBalance employeeWfhBalance = employeeWfhRepository.findByEmployeeId(employeeId);

        WFHTracker wfhTracker= wfhTrackerRepository.findById(wfhTrackerId).get();
        if (wfhTracker == null) {
            throw new RuntimeException("WFH Tracker not found");
        }

        long days = ChronoUnit.DAYS.between(wfhTracker.getStartDate(), wfhTracker.getEndDate()) + 1;
        if (days <= 0) {
            throw new RuntimeException("Invalid WFH days");
        }

        int currentBalance = employeeWfhBalance.getWfhBalance();
        if (currentBalance < days) {
            throw new RuntimeException("Insufficient WFH balance");
        }
        employeeWfhBalance.setWfhBalance((int) (currentBalance - days));

        employeeWfhRepository.save(employeeWfhBalance);
        wfhTracker.setStatus("APPROVED");
        log.info("WFH balance deducted successfully for employeeId: {}. New balance: {}", employeeId, employeeWfhBalance.getWfhBalance());
        wfhTrackerRepository.save(wfhTracker);
        log.info("WFH Tracker status updated to APPROVED for wfhTrackerId: {}", wfhTrackerId);
    }

    @Override
    public void disburseWfhBalance(Long employeeId, Long wfhTrackerId) {
        log.info("Disbursing WFH balance for employeeId: {}, wfhTrackerId: {}", employeeId, wfhTrackerId);

        EmployeeWfhBalance employeeWfhBalance = employeeWfhRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        WFHTracker wfhTracker= wfhTrackerRepository.findById(wfhTrackerId).get();
        if (wfhTracker == null) {
            throw new RuntimeException("WFH Tracker not found");
        }

        long days = ChronoUnit.DAYS.between(wfhTracker.getStartDate(), wfhTracker.getEndDate()) + 1;
        if (days <= 0) {
            throw new RuntimeException("Invalid WFH days");
        }

        int currentBalance = employeeWfhBalance.getWfhBalance();
        employeeWfhBalance.setWfhBalance((int) (currentBalance + days));

        employeeWfhRepository.save(employeeWfhBalance);
        log.info("WFH balance disbursed successfully for employeeId: {}. New balance: {}", employeeId, employeeWfhBalance.getWfhBalance());
    }

    @Override
    public List<WfhBalanceDto> getEmployeeWfhBalances(String employeeId) {
        log.info("Fetching WFH balances for employeeId: {}", employeeId);
        List<EmployeeWfhBalance> balances = employeeWfhBalanceRepository.findByEmployeeId(employeeId);
        log.info("Found {} WFH balance record(s) for employeeId: {}", balances.size(), employeeId);

        return balances.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    public void initializeWfhBalanceForNewEmployee(String employeeId) {
        log.info("initializeWfhBalanceForNewEmployee started - employeeId={}", employeeId);

        String url = companyServiceBaseUrl + "/wfh-types";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<WfhType[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    WfhType[].class
            );

            WfhType[] wfhTypes = response.getBody();
            LocalDate cycleStart = LocalDate.of(2026, 1, 1);
            LocalDate cycleEnd = LocalDate.of(2026, 12, 31);
            log.info("Fetched {} wfh type(s) from {} for employeeId={}",
                    wfhTypes == null ? 0 : wfhTypes.length, url, employeeId);

            if (wfhTypes != null) {
                for (WfhType wfhType : wfhTypes) {
                    double wfhCount = ProrataWfhCalculator.calculateProrataWfh(LocalDate.now(), wfhType.getTotalDays(), ProrataWfhCalculator.Frequency.valueOf(wfhType.getDisbursalFrequency().name()), cycleStart, cycleEnd);
                    log.info("Prorated wfhCount={} for employeeId={} wfhType={} totalDays={} frequency={} cycleStart={} cycleEnd={}",
                            wfhCount, employeeId, wfhType.getName(), wfhType.getTotalDays(),
                            wfhType.getDisbursalFrequency(), cycleStart, cycleEnd);
                    createWfhBalance(employeeId, wfhType, "NEW_EMPLOYEE_INITIALIZATION", wfhCount);
                }
            }
        } catch (Exception e) {
            log.error("Failed to initialize wfh balances for new employee={}: {}", employeeId, e.getMessage(), e);
            throw new RuntimeException("Failed to initialize wfh balances for new employee: " + e.getMessage());
        }
        log.debug("initializeWfhBalanceForNewEmployee completed - employeeId={}", employeeId);
    }

    @Override
    public void initializeWfhBalanceForNewWfhType(WfhType wfhType) {
        log.info("initializeWfhBalanceForNewWfhType started - wfhType={}", wfhType.getName());

        List<Employee> employees = employeeRepository.findAll();
        LocalDate cycleStart = LocalDate.of(2026, 1, 1);
        LocalDate cycleEnd = LocalDate.of(2026, 12, 31);
        log.info("Fetched {} employee(s) for new wfhType={}", employees.size(), wfhType.getName());

        for (Employee employee : employees) {
            double wfhCount = ProrataWfhCalculator.calculateProrataWfh(LocalDate.now(), wfhType.getTotalDays(), ProrataWfhCalculator.Frequency.valueOf(wfhType.getDisbursalFrequency().name()), cycleStart, cycleEnd);
            log.info("Prorated wfhCount={} for employeeId={} wfhType={} totalDays={} frequency={} cycleStart={} cycleEnd={}",
                    wfhCount, employee.getEmployeeId(), wfhType.getName(), wfhType.getTotalDays(),
                    wfhType.getDisbursalFrequency(), cycleStart, cycleEnd);
            createWfhBalance(employee.getEmployeeId(), wfhType, "NEW_WFH_TYPE_INITIALIZATION", wfhCount);
        }
        log.info("initializeWfhBalanceForNewWfhType completed - wfhType={}", wfhType.getName());
    }

    private void createWfhBalance(String employeeId, WfhType wfhType, String reason, double wfhCount) {
        log.debug("createWfhBalance started - employeeId={} wfhType={} reason={} wfhCount={}",
                employeeId, wfhType.getName(), reason, wfhCount);

        EmployeeWfhBalance balance = new EmployeeWfhBalance();
        balance.setEmployeeId(employeeId);
        balance.setWfhTypeName(wfhType.getName());
        balance.setWfhBalance((int) Math.round(wfhCount));

        balance = employeeWfhBalanceRepository.save(balance);
        log.debug("Saved EmployeeWfhBalance id={} employeeId={} wfhType={} wfhBalance={}",
                balance.getId(), employeeId, wfhType.getName(), balance.getWfhBalance());

        createWfhTransaction(employeeId, wfhType.getName(), "INITIALIZATION", wfhCount);
        log.debug("createWfhBalance completed - employeeId={} wfhType={}", employeeId, wfhType.getName());
    }

    private void createWfhTransaction(String employeeId, String wfhTypeName, String transactionType, double days) {
        WFHTransaction transaction = new WFHTransaction();
        transaction.setEmployeeId(employeeId);
        transaction.setWfhTypeName(wfhTypeName);
        transaction.setTransactionType(transactionType);
        transaction.setDays(days);

        wfhTransactionRepository.save(transaction);
    }

    private WfhBalanceDto mapToDto(EmployeeWfhBalance balance) {
        WfhBalanceDto dto = new WfhBalanceDto();
        dto.setWfhTypeName(balance.getWfhTypeName());
        dto.setWfhBalance(balance.getWfhBalance());
        return dto;
    }


}
