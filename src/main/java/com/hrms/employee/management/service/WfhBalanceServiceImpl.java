package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.hrms.employee.management.dao.*;
import com.hrms.employee.management.utility.DisbursalEligibility;
import com.hrms.employee.management.utility.LeaveTransactionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.hrms.employee.management.dto.WfhBalanceDto;
import com.hrms.employee.management.dto.WfhType;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.EmployeeWfhBalanceRepository;
import com.hrms.employee.management.repository.WFHTrackerRepository;
import com.hrms.employee.management.repository.WFHTransactionRepository;
import com.hrms.employee.management.utility.ProrataWfhCalculator;
import com.hrms.employee.management.utility.TenantContext;

import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
public class WfhBalanceServiceImpl implements WfhBalanceService {

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
    @Transactional
    public void deductWfhBalance(String employeeId, Long wfhTrackerId) {
        log.info("Deducting WFH balance for employeeId: {}, wfhTrackerId: {}", employeeId, wfhTrackerId);

        WFHTracker wfhTracker = wfhTrackerRepository.findById(wfhTrackerId)
                .orElseThrow(() -> new RuntimeException("WFH Tracker not found"));
        if (!wfhTracker.getStatus().equals("PENDING")) {
            throw new RuntimeException("WFH Tracker is not in PENDING status");
        }
        if (!wfhTracker.isDeductWfhBalance()){
            wfhTracker.setStatus("APPROVED");
            log.info("WFH balance not deducted for employeeId: {} (deduction not requested)", employeeId);
            wfhTrackerRepository.save(wfhTracker);
            return; // No deduction needed if the flag is false
        }

        long days = ChronoUnit.DAYS.between(wfhTracker.getStartDate(), wfhTracker.getEndDate()) + 1;
        if (days <= 0) {
            throw new RuntimeException("Invalid WFH days");
        }

        // A WFH request carries no WFH type, so it draws on this year's open balances in type-name
        // order. An employee with a single WFH type is simply debited from that one balance.
        int currentYear = Year.now().getValue();
        List<EmployeeWfhBalance> balances = currentYearBalances(employeeId, currentYear);

        long available = balances.stream().mapToLong(WfhBalanceServiceImpl::balanceOf).sum();
        if (available < days) {
            throw new RuntimeException("Insufficient WFH balance");
        }

        long remaining = days;
        for (EmployeeWfhBalance balance : balances) {
            long taken = Math.min(balanceOf(balance), remaining);
            if (taken <= 0) {
                continue;
            }
            double before = availableOf(balance);
            balance.setWfhBalance((int) (balanceOf(balance) - taken));
            employeeWfhBalanceRepository.save(balance);
            createWfhTransaction(employeeId, balance.getWfhTypeName(), "DEBIT", taken, currentYear,
                    before, availableOf(balance), "WFH #" + wfhTrackerId);
            remaining -= taken;
            if (remaining == 0) {
                break;
            }
        }
        log.info("Deducted {} WFH day(s) for employeeId: {} year: {}", days, employeeId, currentYear);
    }

    private static int balanceOf(EmployeeWfhBalance balance) {
        return balance.getWfhBalance() == null ? 0 : balance.getWfhBalance();
    }

    /** What the employee can use from this balance; the figure the ledger records before and after. */
    private static double availableOf(EmployeeWfhBalance balance) {
        return balanceOf(balance) + balance.getCarryForwardDays();
    }

    private static String refundReason(Long wfhTrackerId) {
        return "Refund WFH #" + wfhTrackerId;
    }

    /** This year's open balances for the employee, in the order deduction draws on them. */
    private List<EmployeeWfhBalance> currentYearBalances(String employeeId, int year) {
        return employeeWfhBalanceRepository.findByEmployeeIdAndYearAndIsActiveTrue(employeeId, year).stream()
                .sorted(Comparator.comparing(EmployeeWfhBalance::getWfhTypeName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    /**
     * Gives back the days an approved WFH request deducted, e.g. when the request is withdrawn.
     * A request can be refunded once.
     */
    @Override
    @Transactional
    public void disburseWfhBalance(String employeeId, Long wfhTrackerId) {
        log.info("Refunding WFH balance for employeeId: {}, wfhTrackerId: {}", employeeId, wfhTrackerId);

        WFHTracker wfhTracker = wfhTrackerRepository.findById(wfhTrackerId)
                .orElseThrow(() -> new BusinessException("WFH Tracker not found"));
        if (!wfhTracker.getEmployee().getEmployeeId().equals(employeeId)) {
            throw new BusinessException("WFH Tracker does not belong to the specified employee");
        }
        if (!"APPROVED".equalsIgnoreCase(wfhTracker.getStatus()) || !wfhTracker.isDeductWfhBalance()) {
            throw new BusinessException("WFH request " + wfhTrackerId + " did not deduct any WFH balance");
        }
        String reason = refundReason(wfhTrackerId);
        if (wfhTransactionRepository.existsByEmployeeIdAndReason(employeeId, reason)) {
            throw new BusinessException("WFH request " + wfhTrackerId + " has already been refunded");
        }

        long days = ChronoUnit.DAYS.between(wfhTracker.getStartDate(), wfhTracker.getEndDate()) + 1;
        if (days <= 0) {
            throw new BusinessException("Invalid WFH days");
        }

        // Credited to the balance that deduction draws on first.
        int currentYear = Year.now().getValue();
        EmployeeWfhBalance balance = currentYearBalances(employeeId, currentYear).stream().findFirst()
                .orElseThrow(() -> new BusinessException("No active WFH balance for employee " + employeeId + " in " + currentYear));

        double before = availableOf(balance);
        balance.setWfhBalance((int) (balanceOf(balance) + days));
        employeeWfhBalanceRepository.save(balance);
        createWfhTransaction(employeeId, balance.getWfhTypeName(), "CREDIT", days, currentYear, before, availableOf(balance), reason);
        log.info("WFH balance refunded for employeeId: {}. New balance: {}", employeeId, balance.getWfhBalance());
    }

    @Override
    public List<WfhBalanceDto> getEmployeeWfhBalances(String employeeId) {
        log.info("Fetching WFH balances for employeeId: {}", employeeId);
        int currentYear = Year.now().getValue();
        List<EmployeeWfhBalance> balances = employeeWfhBalanceRepository
                .findByEmployeeIdAndYearAndIsActiveTrue(employeeId, currentYear);
        log.info("Found {} WFH balance record(s) for employeeId: {} year: {}", balances.size(), employeeId, currentYear);

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
            LocalDate today = LocalDate.now();
            LocalDate cycleStart = today.with(TemporalAdjusters.firstDayOfYear());
            LocalDate cycleEnd = today.with(TemporalAdjusters.lastDayOfYear());
            log.info("Fetched {} wfh type(s) from {} for employeeId={}",
                    wfhTypes == null ? 0 : wfhTypes.length, url, employeeId);

            if (wfhTypes != null) {
                for (WfhType wfhType : wfhTypes) {
                    double wfhCount = ProrataWfhCalculator.calculateProrataWfh(today, wfhType.getTotalDays(), ProrataWfhCalculator.Frequency.valueOf(wfhType.getDisbursalFrequency().name()), cycleStart, cycleEnd);
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

        List<Employee> employees = employeeRepository.findAll().stream()
                .filter(DisbursalEligibility::isActive)
                .collect(Collectors.toList());
        LocalDate today = LocalDate.now();
        LocalDate cycleStart = today.with(TemporalAdjusters.firstDayOfYear());
        LocalDate cycleEnd = today.with(TemporalAdjusters.lastDayOfYear());
        log.info("Fetched {} active employee(s) for new wfhType={}", employees.size(), wfhType.getName());

        for (Employee employee : employees) {
            double wfhCount = ProrataWfhCalculator.calculateProrataWfh(today, wfhType.getTotalDays(), ProrataWfhCalculator.Frequency.valueOf(wfhType.getDisbursalFrequency().name()), cycleStart, cycleEnd);
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
        balance.setCarryForwardDays(0);
        balance.setYear(Year.now().getValue());
        balance.setActive(true);

        balance = employeeWfhBalanceRepository.save(balance);
        log.debug("Saved EmployeeWfhBalance id={} employeeId={} wfhType={} wfhBalance={}",
                balance.getId(), employeeId, wfhType.getName(), balance.getWfhBalance());

        // Ledger records what was actually credited (the rounded whole days), not the unrounded prorata.
        createWfhTransaction(employeeId, wfhType.getName(), "INITIALIZATION", balance.getWfhBalance(),
                balance.getYear(), 0, availableOf(balance), reason);
        log.debug("createWfhBalance completed - employeeId={} wfhType={}", employeeId, wfhType.getName());
    }

    private void createWfhTransaction(String employeeId, String wfhTypeName, String transactionType, double days,
                                      int year, double balanceBefore, double balanceAfter, String reason) {
        WFHTransaction transaction = new WFHTransaction();
        transaction.setEmployeeId(employeeId);
        transaction.setWfhTypeName(wfhTypeName);
        transaction.setTransactionType(transactionType);
        transaction.setDays(days);
        transaction.setYear(year);
        transaction.setBalanceBefore(balanceBefore);
        transaction.setBalanceAfter(balanceAfter);
        transaction.setReason(reason);
        wfhTransactionRepository.save(transaction);
    }

    private WfhBalanceDto mapToDto(EmployeeWfhBalance balance) {
        WfhBalanceDto dto = new WfhBalanceDto();
        dto.setWfhTypeName(balance.getWfhTypeName());
        dto.setWfhBalance(balance.getWfhBalance());
        dto.setCarryForwardDays(balance.getCarryForwardDays());
        dto.setRemainingDays((Double)balance.getRemainingDays());
        dto.setYear(balance.getYear());

        return dto;
    }


}
