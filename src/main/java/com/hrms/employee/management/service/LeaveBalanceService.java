package com.hrms.employee.management.service;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dao.LeaveTransaction;
import com.hrms.employee.management.dto.BulkLeaveAssignmentDto;
import com.hrms.employee.management.dto.LeaveBalanceDto;
import com.hrms.employee.management.dto.LeaveDeductionDto;
import com.hrms.employee.management.dto.LeaveTypeDto;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTrackerRepository;
import com.hrms.employee.management.repository.EmployeeLeaveBalanceRepository;
import com.hrms.employee.management.repository.LeaveTransactionRepository;
import com.hrms.employee.management.utility.LeaveTransactionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.stream.Collectors;

@Service
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

    @Value("${company.service.url}")
    private String companyServiceBaseUrl;

    public List<LeaveBalanceDto> getEmployeeLeaveBalances(String employeeId) {
        int currentYear = Year.now().getValue();
        List<EmployeeLeaveBalance> balances = leaveBalanceRepository
                .findByEmployeeIdAndYearAndIsActiveTrue(employeeId, currentYear);

        return balances.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    // public LeaveBalanceDto getEmployeeLeaveBalance(String employeeId, String
    // leaveTypeId) {
    // int currentYear = Year.now().getValue();
    // EmployeeLeaveBalance balance = leaveBalanceRepository
    // .findByEmployeeIdAndLeaveTypeIdAndYearAndIsActiveTrue(employeeId,
    // leaveTypeId, currentYear)
    // .orElseThrow(() -> new RuntimeException("Leave balance not found"));

    // return mapToDto(balance);
    // }

    public void initializeLeaveBalanceForNewEmployee(String employeeId) {

        String url = companyServiceBaseUrl + "/leave-types";
        try {
            LeaveTypeDto[] leaveTypes = restTemplate.getForObject(url, LeaveTypeDto[].class);

            if (leaveTypes != null) {
                int currentYear = Year.now().getValue();
                for (LeaveTypeDto leaveType : leaveTypes) {
                    createLeaveBalance(employeeId, leaveType, currentYear, "NEW_EMPLOYEE_INITIALIZATION");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize leave balances for new employee: " + e.getMessage());
        }
    }

    public void initializeLeaveBalanceForNewLeaveType(LeaveTypeDto leaveType) {
        List<Employee> employees = employeeRepository.findAll();
        int currentYear = Year.now().getValue();

        for (Employee employee : employees) {
            createLeaveBalance(employee.getEmployeeId(), leaveType, currentYear, "NEW_LEAVE_TYPE_INITIALIZATION");
        }
    }

    // public void assignLeaveToEmployee(String employeeId, String leaveTypeId, int
    // days, String reason) {
    // int currentYear = Year.now().getValue();
    // EmployeeLeaveBalance balance = leaveBalanceRepository
    // .findByEmployeeIdAndLeaveTypeIdAndYearAndIsActiveTrue(employeeId,
    // leaveTypeId, currentYear)
    // .orElseThrow(() -> new RuntimeException("Leave balance not found"));

    // int balanceBefore = balance.getRemainingDays();
    // balance.addDays(days);
    // leaveBalanceRepository.save(balance);

    // createLeaveTransaction(employeeId, leaveTypeId, balance.getLeaveTypeName(),
    // LeaveTransactionType.CREDIT, days, balanceBefore, balance.getRemainingDays(),
    // reason);
    // }

    // public void bulkAssignLeave(BulkLeaveAssignmentDto assignmentDto) {
    // List<Employee> employees = employeeRepository.findAll();

    // for (Employee employee : employees) {
    // try {
    // assignLeaveToEmployee(employee.getEmployeeId(),
    // assignmentDto.getLeaveTypeId(),
    // assignmentDto.getDays(), assignmentDto.getReason());
    // } catch (Exception e) {
    // System.err.println("Failed to assign leave to employee " +
    // employee.getEmployeeId() + ": " + e.getMessage());
    // }
    // }
    // }

    public void deductLeaveFromEmployee(String employeeId, Long leaveId) {

        LeaveTracker leaveTracker = leaveTrackerRepository.findById(leaveId)
                .orElseThrow(() -> new RuntimeException("Leave not found"));
        EmployeeLeaveBalance balance = leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeName(employeeId, leaveTracker.getLeaveType()).get();
        int days = leaveTracker.getEndDate().getDayOfYear() - leaveTracker.getStartDate().getDayOfYear() + 1;
        Double updateBalance = balance.getLeaveBalance() - days;
        balance.setLeaveBalance(updateBalance);
        balance.setRemainingDays(updateBalance);
        leaveBalanceRepository.save(balance);

        LeaveTransaction transaction = new LeaveTransaction();
        transaction.setEmployeeId(employeeId);
        transaction.setLeaveTypeName(balance.getLeaveTypeName());
        transaction.setTransactionType(LeaveTransactionType.DEBIT);
        transaction.setDays(days);
        leaveTransactionRepository.save(transaction);
    }


    private void createLeaveBalance(String employeeId, LeaveTypeDto leaveType, int year, String reason) {

        int totalDays = leaveType.getTotalDays();
        double allocatedDays = 0;

        LocalDate today = LocalDate.now();
        int month = today.getMonthValue();
        int day = today.getDayOfMonth();

        if(leaveType.getDisbursalFrequency().equals("HALF_YEARLY")){
            int monthsRemaining;
            if (month < 6 ) {
                 monthsRemaining = 6 - month+1;
                allocatedDays = (monthsRemaining * totalDays)/6;
            }
            else{
                monthsRemaining = 12 - month+1;
                allocatedDays = (monthsRemaining * totalDays)/6;
            }
        } else if(leaveType.getDisbursalFrequency().equals("QUARTERLY")){
            int monthsRemaining;
            if (month <=3 ) {
                monthsRemaining = 4 - month+1;
            }
            else if(month<=6){
                monthsRemaining = 7 - month+1;
            }
            else if(month<=9){
                monthsRemaining = 10 - month+1;
            }
            else{
                monthsRemaining = 12 - month+1;
            }
            allocatedDays = (monthsRemaining * totalDays)/4;
            allocatedDays= Math.round(allocatedDays * 100.0) / 100.0;
        }
        else if(leaveType.getDisbursalFrequency().equals("MONTHLY")){
            double monthlyPortion = (double) totalDays / 12;
            monthlyPortion = Math.round(monthlyPortion * 100.0) / 100.0; 
            allocatedDays = (day <= 15) ? monthlyPortion : monthlyPortion / 2;
        }
        else {
            double allocateDays=(double) totalDays/12;
            allocateDays = Math.round(allocateDays * 100.0) / 100.0;
            int monthsRemaining = 12 - month + 1;
            allocatedDays =monthsRemaining * allocateDays;
        }

        EmployeeLeaveBalance balance = new EmployeeLeaveBalance();
        balance.setEmployeeId(employeeId);
        // balance.setLeaveTypeId(leaveType.getId());
        balance.setLeaveBalance(allocatedDays);
        balance.setRemainingDays(leaveType.getTotalDays());
        balance.setLeaveTypeName(leaveType.getName());
        // balance.setAllocatedDays(leaveType.getDefaultTotalDays());
        // balance.setUsedDays(0);
        balance.setCarryForwardDays(0);
        balance.setYear(year);
        balance.setActive(true);

        leaveBalanceRepository.save(balance);

        createLeaveTransaction(employeeId, leaveType.getId(), leaveType.getName(),
                LeaveTransactionType.INITIALIZATION, leaveType.getTotalDays(), 0, leaveType.getTotalDays(), reason);
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