package com.hrms.employee.management.service;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dao.LeaveTransaction;
import com.hrms.employee.management.dto.BulkLeaveAssignmentDto;
import com.hrms.employee.management.dto.LeaveBalanceDto;
import com.hrms.employee.management.dto.LeaveDeductionDto;
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

    @Value("${company.service.base.url}")
    private String companyServiceBaseUrl;

    public List<LeaveBalanceDto> getEmployeeLeaveBalances(String employeeId) {
        int currentYear = Year.now().getValue();
        List<EmployeeLeaveBalance> balances = leaveBalanceRepository
                .findByEmployeeIdAndYearAndIsActiveTrue(employeeId, currentYear);

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

        String url = companyServiceBaseUrl + "/leave-types";
        try {
            LeaveType[] leaveTypes = restTemplate.getForObject(url, LeaveType[].class);

            if (leaveTypes != null) {
                int currentYear = Year.now().getValue();
                for (LeaveType leaveType : leaveTypes) {
                    createLeaveBalance(employeeId, leaveType, currentYear, "NEW_EMPLOYEE_INITIALIZATION");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize leave balances for new employee: " + e.getMessage());
        }
    }

    public void initializeLeaveBalanceForNewLeaveType(LeaveType leaveType) {
        List<Employee> employees = employeeRepository.findAll();
        int currentYear = Year.now().getValue();

        for (Employee employee : employees) {
            createLeaveBalance(employee.getEmployeeId(), leaveType, currentYear, "NEW_LEAVE_TYPE_INITIALIZATION");
        }
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

        LeaveTracker leaveTracker=leaveTrackerRepository.findById(leaveId).orElseThrow(() -> new RuntimeException("Leave not found"));
        EmployeeLeaveBalance balance = leaveBalanceRepository.findByEmployeeIdAndLeaveTypeName(employeeId,leaveTracker.getLeaveType()).get();
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

    // public void deactivateLeaveType(String leaveTypeId) {
    //     List<EmployeeLeaveBalance> balances = leaveBalanceRepository.findByLeaveTypeIdAndIsActiveTrue(leaveTypeId);

    //     for (EmployeeLeaveBalance balance : balances) {
    //         balance.setActive(false);
    //     }

    //     leaveBalanceRepository.saveAll(balances);
    // }

    private void createLeaveBalance(String employeeId, LeaveType leaveType, int year, String reason) {
        EmployeeLeaveBalance balance = new EmployeeLeaveBalance();
        balance.setEmployeeId(employeeId);
        // balance.setLeaveTypeId(leaveType.getId());
        balance.setLeaveTypeName(leaveType.getName());
        // balance.setAllocatedDays(leaveType.getDefaultTotalDays());
        // balance.setUsedDays(0);
        balance.setCarryForwardDays(0);
        balance.setYear(year);
        balance.setActive(true);

        leaveBalanceRepository.save(balance);

        createLeaveTransaction(employeeId, leaveType.getId(), leaveType.getName(),
                LeaveTransactionType.INITIALIZATION, leaveType.getDefaultTotalDays(), 0, leaveType.getDefaultTotalDays(), reason);
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

    // Isko theek karna
    public static class LeaveType {
        private String id;
        private String name;
        private int defaultTotalDays;
        private boolean carryForward;
        private int maxCarryForwardDays;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getDefaultTotalDays() {
            return defaultTotalDays;
        }

        public void setDefaultTotalDays(int defaultTotalDays) {
            this.defaultTotalDays = defaultTotalDays;
        }

        public boolean isCarryForward() {
            return carryForward;
        }

        public void setCarryForward(boolean carryForward) {
            this.carryForward = carryForward;
        }

        public int getMaxCarryForwardDays() {
            return maxCarryForwardDays;
        }

        public void setMaxCarryForwardDays(int maxCarryForwardDays) {
            this.maxCarryForwardDays = maxCarryForwardDays;
        }
    }
}