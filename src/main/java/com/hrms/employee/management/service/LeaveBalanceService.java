package com.hrms.employee.management.service;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dao.LeaveTransaction;
import com.hrms.employee.management.dto.LeaveBalanceDto;
import com.hrms.employee.management.dto.LeaveType;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTrackerRepository;
import com.hrms.employee.management.repository.EmployeeLeaveBalanceRepository;
import com.hrms.employee.management.repository.LeaveTransactionRepository;
import com.hrms.employee.management.utility.DisbursalEligibility;
import com.hrms.employee.management.utility.LeaveTransactionType;
import com.hrms.employee.management.utility.ProrataLeaveCalculator;
import com.hrms.employee.management.utility.TenantContext;
import com.hrms.employee.management.utility.WorkingDays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Year;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.Collectors;
import java.time.LocalDate;

import lombok.extern.log4j.Log4j2;


@Service
@Log4j2
@Transactional
public class LeaveBalanceService {


    @Autowired
    private LeaveTrackerRepository leaveTrackerRepository;

    @Autowired
    private EmployeeLeaveBalanceRepository leaveBalanceRepository;

    @Autowired
    private LeaveTransactionRepository leaveTransactionRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${company.service.base.url}")
    private String companyServiceBaseUrl;

    public List<LeaveBalanceDto> getEmployeeLeaveBalances(String employeeId) {
        int currentYear = Year.now().getValue();
        log.info("getEmployeeLeaveBalances started - employeeId={} year={}", employeeId, currentYear);
        List<EmployeeLeaveBalance> balances = leaveBalanceRepository
                .findByEmployeeIdAndYearAndIsActiveTrue(employeeId, currentYear);
        log.info("Fetched {} leave balance(s) for employeeId={} year={}", balances.size(), employeeId, currentYear);

        return balances.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    // public LeaveBalanceDto getEmployeeLeaveBalance(String employeeId, String leaveTypeId) {
    //     int currentYear = Year.now().getValue();
    //     EmployeeLeaveBalance balance = leaveBalanceRepository
    //             .findByEmployeeIdAndLeaveTypeIdAndYearAndIsActiveTrue(employeeId, leaveTypeId, currentYear)
    //             .orElseThrow(() -> new RuntimeException("Leave balance not found"));

    //     return mapToDto(balance);
    // }

    public void initializeLeaveBalanceForNewEmployee(String employeeId) {
        log.info("initializeLeaveBalanceForNewEmployee started - employeeId={}", employeeId);

        String url = companyServiceBaseUrl + "/leave-types";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Tenant-Id", TenantContext.getCurrentTenant());

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<LeaveType[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    LeaveType[].class
            );

            LeaveType[] leaveTypes = response.getBody();
            LocalDate today = LocalDate.now();
            LocalDate cycleStart = today.with(TemporalAdjusters.firstDayOfYear());
            LocalDate cycleEnd = today.with(TemporalAdjusters.lastDayOfYear());
            log.info("Fetched {} leave type(s) from {} for employeeId={}",
                    leaveTypes == null ? 0 : leaveTypes.length, url, employeeId);

            if (leaveTypes != null) {
                int currentYear = today.getYear();
                for (LeaveType leaveType : leaveTypes) {
                    double leavesCount = ProrataLeaveCalculator.calculateProrataLeaves(today, leaveType.getTotalDays(), ProrataLeaveCalculator.Frequency.valueOf(leaveType.getDisbursalFrequency().name()), cycleStart, cycleEnd);
                    log.info("Prorated leavesCount={} for employeeId={} leaveType={} totalDays={} frequency={} cycleStart={} cycleEnd={}",
                            leavesCount, employeeId, leaveType.getName(), leaveType.getTotalDays(),
                            leaveType.getDisbursalFrequency(), cycleStart, cycleEnd);
                    createLeaveBalance(employeeId, leaveType, currentYear, "NEW_EMPLOYEE_INITIALIZATION", leavesCount);
                }
            }
        } catch (Exception e) {
            log.error("Failed to initialize leave balances for new employee={}: {}", employeeId, e.getMessage(), e);
            throw new RuntimeException("Failed to initialize leave balances for new employee: " + e.getMessage());
        }
        log.debug("initializeLeaveBalanceForNewEmployee completed - employeeId={}", employeeId);
    }

    public void initializeLeaveBalanceForNewLeaveType(LeaveType leaveType) {
        log.info("initializeLeaveBalanceForNewLeaveType started - leaveType={}", leaveType.getName());

        List<Employee> employees = employeeRepository.findAll().stream()
                .filter(DisbursalEligibility::isActive)
                .collect(Collectors.toList());
        LocalDate today = LocalDate.now();
        LocalDate cycleStart = today.with(TemporalAdjusters.firstDayOfYear());
        LocalDate cycleEnd = today.with(TemporalAdjusters.lastDayOfYear());
        int currentYear = today.getYear();
        log.info("Fetched {} active employee(s) for new leaveType={}", employees.size(), leaveType.getName());

        for (Employee employee : employees) {
            double leavesCount = ProrataLeaveCalculator.calculateProrataLeaves(today, leaveType.getTotalDays(), ProrataLeaveCalculator.Frequency.valueOf(leaveType.getDisbursalFrequency().name()), cycleStart, cycleEnd);
            log.info("Prorated leavesCount={} for employeeId={} leaveType={} totalDays={} frequency={} cycleStart={} cycleEnd={}",
                    leavesCount, employee.getEmployeeId(), leaveType.getName(), leaveType.getTotalDays(),
                    leaveType.getDisbursalFrequency(), cycleStart, cycleEnd);
            createLeaveBalance(employee.getEmployeeId(), leaveType, currentYear, "NEW_LEAVE_TYPE_INITIALIZATION", leavesCount);
        }
        log.info("initializeLeaveBalanceForNewLeaveType completed - leaveType={}", leaveType.getName());
    }

    // public void assignLeaveToEmployee(String employeeId, String leaveTypeId, int days, String reason) {
    //     int currentYear = Year.now().getValue();
    //     EmployeeLeaveBalance balance = leaveBalanceRepository
    //             .findByEmployeeIdAndLeaveTypeIdAndYearAndIsActiveTrue(employeeId, leaveTypeId, currentYear)
    //             .orElseThrow(() -> new RuntimeException("Leave balance not found"));

    //     int balanceBefore = balance.getRemainingDays();
    //     balance.addDays(days);
    //     leaveBalanceRepository.save(balance);

    //     createLeaveTransaction(employeeId, leaveTypeId, balance.getLeaveTypeName(),
    //             LeaveTransactionType.CREDIT, days, balanceBefore, balance.getRemainingDays(), reason);
    // }

    // public void bulkAssignLeave(BulkLeaveAssignmentDto assignmentDto) {
    //     List<Employee> employees = employeeRepository.findAll();

    //     for (Employee employee : employees) {
    //         try {
    //             assignLeaveToEmployee(employee.getEmployeeId(), assignmentDto.getLeaveTypeId(),
    //                     assignmentDto.getDays(), assignmentDto.getReason());
    //         } catch (Exception e) {
    //             System.err.println("Failed to assign leave to employee " + employee.getEmployeeId() + ": " + e.getMessage());
    //         }
    //     }
    // }

    public void deductLeaveFromEmployee(String employeeId,Long leaveId) {
        log.info("deductLeaveFromEmployee started - employeeId={} leaveId={}", employeeId, leaveId);

        LeaveTracker leaveTracker=leaveTrackerRepository.findById(leaveId).orElseThrow(() -> new RuntimeException("Leave not found"));
        // Deduct from the current year's open balance; earlier years are closed by the year-end rollover.
        int currentYear = Year.now().getValue();
        long days = WorkingDays.between(leaveTracker.getStartDate(), leaveTracker.getEndDate());
        if (days == 0) {
            log.info("Leave {} for employeeId={} covers no working days. Nothing to deduct.", leaveId, employeeId);
            return;
        }
        // Locked, so concurrent approvals for this employee are checked one after the other.
        EmployeeLeaveBalance balance = leaveBalanceRepository
                .findActiveForUpdate(employeeId, leaveTracker.getLeaveType(), currentYear)
                .orElseThrow(() -> new BusinessException("No active " + leaveTracker.getLeaveType()
                        + " balance for employee " + employeeId + " in " + currentYear));
        double availableBefore = balance.getAvailableDays();
        // The apply-time check can be stale: other leaves may have been approved since.
        if (days > availableBefore) {
            throw new BusinessException("Insufficient " + leaveTracker.getLeaveType() + " balance to approve this leave: "
                    + days + " day(s) requested, " + availableBefore + " available");
        }
        balance.deductDays(days);
        leaveBalanceRepository.save(balance);
        log.info("Deducted {} day(s) for employeeId={} leaveType={} availableBefore={} availableAfter={}",
                days, employeeId, balance.getLeaveTypeName(), availableBefore, balance.getAvailableDays());

        LeaveTransaction transaction = new LeaveTransaction();
        transaction.setEmployeeId(employeeId);
        transaction.setLeaveTypeName(balance.getLeaveTypeName());
        transaction.setTransactionType(LeaveTransactionType.DEBIT);
        transaction.setDays(days);
        transaction.setYear(currentYear);
        transaction.setBalanceBefore(availableBefore);
        transaction.setBalanceAfter(balance.getAvailableDays());
        transaction.setReason("Leave #" + leaveId);
        leaveTransactionRepository.save(transaction);
        log.info("deductLeaveFromEmployee completed - employeeId={} leaveId={}", employeeId, leaveId);
    }

    // public void deactivateLeaveType(String leaveTypeId) {
    //     List<EmployeeLeaveBalance> balances = leaveBalanceRepository.findByLeaveTypeIdAndIsActiveTrue(leaveTypeId);

    //     for (EmployeeLeaveBalance balance : balances) {
    //         balance.setActive(false);
    //     }

    //     leaveBalanceRepository.saveAll(balances);
    // }

    private void createLeaveBalance(String employeeId, LeaveType leaveType, int year, String reason, double leavesCount) {
        log.debug("createLeaveBalance started - employeeId={} leaveType={} year={} reason={} leavesCount={}",
                employeeId, leaveType.getName(), year, reason, leavesCount);

        EmployeeLeaveBalance balance = new EmployeeLeaveBalance();
        balance.setEmployeeId(employeeId);
        // balance.setLeaveTypeId(leaveType.getId());
        balance.setLeaveTypeName(leaveType.getName());
        balance.setLeaveBalance(leavesCount);
        balance.setCarryForwardDays(0);
        balance.setYear(year);
        balance.setActive(true);

        balance = leaveBalanceRepository.save(balance);
        log.debug("Saved EmployeeLeaveBalance id={} employeeId={} leaveType={} leaveBalance={} remainingDays={}",
                balance.getId(), employeeId, leaveType.getName(), balance.getLeaveBalance(), balance.getRemainingDays());

        // Ledger records what was actually credited (the prorated count), not the type's annual total.
        createLeaveTransaction(employeeId, leaveType.getId(), leaveType.getName(),
                LeaveTransactionType.INITIALIZATION, leavesCount, year, 0, balance.getAvailableDays(), reason);
        log.debug("createLeaveBalance completed - employeeId={} leaveType={}", employeeId, leaveType.getName());
    }

    private void createLeaveTransaction(String employeeId, String leaveTypeId, String leaveTypeName,
                                        LeaveTransactionType transactionType, double days, int year,
                                        double balanceBefore, double balanceAfter, String reason) {
        LeaveTransaction transaction = new LeaveTransaction();
        transaction.setEmployeeId(employeeId);
        // transaction.setLeaveTypeId(leaveTypeId);
        transaction.setLeaveTypeName(leaveTypeName);
        transaction.setTransactionType(transactionType);
        transaction.setDays(days);
        transaction.setYear(year);
        transaction.setBalanceBefore(balanceBefore);
        transaction.setBalanceAfter(balanceAfter);
        transaction.setReason(reason);
        // transaction.setProcessedBy("DAD");

        leaveTransactionRepository.save(transaction);
    }

    private LeaveBalanceDto mapToDto(EmployeeLeaveBalance balance) {
        LeaveBalanceDto dto = new LeaveBalanceDto();
        dto.setLeaveTypeName(balance.getLeaveTypeName());
        dto.setLeaveBalance(balance.getLeaveBalance());
        dto.setCarryForwardDays(balance.getCarryForwardDays());
        dto.setRemainingDays(balance.getRemainingDays());
        dto.setYear(balance.getYear());
        return dto;
    }
}