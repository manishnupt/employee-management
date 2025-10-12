package com.hrms.employee.management.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hrms.employee.management.dao.EmployeeWfhBalance;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.repository.EmployeeWfhRepository;
import com.hrms.employee.management.repository.WFHTrackerRepository;

@Service
public class WfhBalanceServiceImpl implements WfhBalanceService {

    @Autowired
    private EmployeeWfhRepository employeeWfhRepository;

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
        
        int days = wfhTracker.getEndDate().getDayOfYear() - wfhTracker.getStartDate().getDayOfYear() + 1;
        if (days <= 0) {
            throw new RuntimeException("Invalid WFH days");
        }

        int currentBalance = employeeWfhBalance.getWfhBalance();
        if (currentBalance < days) {
            throw new RuntimeException("Insufficient WFH balance");
        }
        employeeWfhBalance.setWfhBalance(currentBalance - days);

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
        
        int days = wfhTracker.getEndDate().getDayOfYear() - wfhTracker.getStartDate().getDayOfYear() + 1;
        if (days <= 0) {
            throw new RuntimeException("Invalid WFH days");
        }

        int currentBalance = employeeWfhBalance.getWfhBalance();
        employeeWfhBalance.setWfhBalance(currentBalance + days);

        employeeWfhRepository.save(employeeWfhBalance);
    }

    
    


}
