package com.hrms.employee.management.service;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dao.LeaveTransaction;
import com.hrms.employee.management.dto.LeaveBalanceDto;
import com.hrms.employee.management.dto.LeaveType;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTrackerRepository;
import com.hrms.employee.management.repository.EmployeeLeaveBalanceRepository;
import com.hrms.employee.management.repository.LeaveTransactionRepository;
import com.hrms.employee.management.utility.LeaveTransactionType;
import com.hrms.employee.management.utility.ProrataLeaveCalculator;
import com.hrms.employee.management.utility.TenantContext;
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
import java.time.temporal.ChronoUnit;
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
            LocalDate cycleStart = LocalDate.of(2026, 1, 1);
            LocalDate cycleEnd = LocalDate.of(2026, 12, 31);
            log.info("Fetched {} leave type(s) from {} for employeeId={}",
                    leaveTypes == null ? 0 : leaveTypes.length, url, employeeId);

            if (leaveTypes != null) {
                int currentYear = Year.now().getValue();
                for (LeaveType leaveType : leaveTypes) {
                    double leavesCount = ProrataLeaveCalculator.calculateProrataLeaves(LocalDate.now(), leaveType.getTotalDays(), ProrataLeaveCalculator.Frequency.valueOf(leaveType.getDisbursalFrequency().name()), cycleStart, cycleEnd);
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

        List<Employee> employees = employeeRepository.findAll();
        int currentYear = Year.now().getValue();
        LocalDate cycleStart = LocalDate.of(2026, 1, 1);
        LocalDate cycleEnd = LocalDate.of(2026, 12, 31);
        log.info("Fetched {} employee(s) for new leaveType={}", employees.size(), leaveType.getName());

        for (Employee employee : employees) {
            double leavesCount = ProrataLeaveCalculator.calculateProrataLeaves(LocalDate.now(), leaveType.getTotalDays(), ProrataLeaveCalculator.Frequency.valueOf(leaveType.getDisbursalFrequency().name()), cycleStart, cycleEnd);
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
        EmployeeLeaveBalance balance = leaveBalanceRepository.findByEmployeeIdAndLeaveTypeName(employeeId,leaveTracker.getLeaveType()).get();
        long days = ChronoUnit.DAYS.between(leaveTracker.getStartDate(), leaveTracker.getEndDate()) + 1;
        Double updateBalance = balance.getLeaveBalance() - days;
        balance.setLeaveBalance(updateBalance);
        balance.setRemainingDays(updateBalance);
        leaveBalanceRepository.save(balance);
        log.info("Deducted {} day(s) for employeeId={} leaveType={} newBalance={}",
                days, employeeId, balance.getLeaveTypeName(), updateBalance);

        LeaveTransaction transaction = new LeaveTransaction();
        transaction.setEmployeeId(employeeId);
        transaction.setLeaveTypeName(balance.getLeaveTypeName());
        transaction.setTransactionType(LeaveTransactionType.DEBIT);
        transaction.setDays(days);
        leaveTransactionRepository.save(transaction);
        log.info("deductLeaveFromEmployee completed - employeeId={} leaveId={}", employeeId, leaveId);
        leaveTracker.setStatus("APPROVED");
        leaveTrackerRepository.save(leaveTracker);
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

        createLeaveTransaction(employeeId, leaveType.getId(), leaveType.getName(),
                LeaveTransactionType.INITIALIZATION, leaveType.getTotalDays(), 0, leaveType.getTotalDays(), reason);
        log.debug("createLeaveBalance completed - employeeId={} leaveType={}", employeeId, leaveType.getName());
    }

    private void createLeaveTransaction(String employeeId, String leaveTypeId, String leaveTypeName,
                                        LeaveTransactionType transactionType, int days, int balanceBefore, int balanceAfter, String reason) {
        LeaveTransaction transaction = new LeaveTransaction();
        transaction.setEmployeeId(employeeId);
        // transaction.setLeaveTypeId(leaveTypeId);
        transaction.setLeaveTypeName(leaveTypeName);
        transaction.setTransactionType(transactionType);
        transaction.setDays(days);
        // transaction.setBalanceBefore(balanceBefore);
        // transaction.setBalanceAfter(balanceAfter);
        // transaction.setReason(reason);
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