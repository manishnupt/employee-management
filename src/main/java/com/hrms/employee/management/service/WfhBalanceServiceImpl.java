package com.hrms.employee.management.service;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hrms.employee.management.dao.EmployeeWfhBalance;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.dto.WfhBalanceDto;
import com.hrms.employee.management.repository.EmployeeWfhBalanceRepository;
import com.hrms.employee.management.repository.EmployeeWfhRepository;
import com.hrms.employee.management.repository.WFHTrackerRepository;

@Service
public class WfhBalanceServiceImpl implements WfhBalanceService {

    @Autowired
    private EmployeeWfhRepository employeeWfhRepository;

    @Autowired
    private EmployeeWfhBalanceRepository employeeWfhBalanceRepository;

    @Autowired
    private WFHTrackerRepository wfhTrackerRepository;

    @Override
    public void deductWfhBalance(Long employeeId, Long wfhTrackerId) {


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
        if (currentBalance < days) {
            throw new RuntimeException("Insufficient WFH balance");
        }
        employeeWfhBalance.setWfhBalance((int) (currentBalance - days));

        employeeWfhRepository.save(employeeWfhBalance);
    }

    @Override
    public void disburseWfhBalance(Long employeeId, Long wfhTrackerId) {
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
    }

    @Override
    public List<WfhBalanceDto> getEmployeeWfhBalances(String employeeId) {
        List<EmployeeWfhBalance> balances = employeeWfhBalanceRepository.findByEmployeeId(employeeId);

        return balances.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    private WfhBalanceDto mapToDto(EmployeeWfhBalance balance) {
        WfhBalanceDto dto = new WfhBalanceDto();
        dto.setWfhTypeName(balance.getWfhTypeName());
        dto.setWfhBalance(balance.getWfhBalance());
        return dto;
    }


}
