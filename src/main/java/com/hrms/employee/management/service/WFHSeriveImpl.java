package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.util.List;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.dto.WFHTrackerRequest;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.WFHTrackerRepository;

@Log4j2
@Service
public class WFHSeriveImpl implements WFHService {

    private final WFHTrackerRepository wfhRepository;
    private final EmployeeRepository employeeRepository;
    private ActionItemService actionItemService;

    public WFHSeriveImpl(WFHTrackerRepository wfhRepository,EmployeeRepository employeeRepository,
            ActionItemService actionItemService) {
        this.actionItemService = actionItemService;
        this.wfhRepository = wfhRepository;
        this.employeeRepository = employeeRepository;
    }

    public WFHTracker applyWFH(String employeeId, WFHTrackerRequest workFromHomeRequest) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        WFHTracker workFromHome = WFHTracker.builder()
                .startDate(workFromHomeRequest.getStartDate())
                .endDate(workFromHomeRequest.getEndDate())
                .reason(workFromHomeRequest.getReason())
                .status("PENDING")
                .build();
        workFromHome.setEmployee(employee);
        log.info("Creating action item for WFH request");
        WFHTracker wfhTracker = wfhRepository.save(workFromHome);


        actionItemService.createActionItem(employeeId,wfhTracker,employee.getAssignedManagerId());

        return wfhTracker;
    }

    public List<WFHTracker> getWFHHistory(String employeeId) {

        List<WFHTracker> wfhTrackers = wfhRepository.findAllByEmployee_EmployeeId(employeeId);
        return wfhTrackers;
    }

    public WFHTracker getWFHDetailsById(String employeeId, Long id) {
        WFHTracker wfhTracker = wfhRepository.findByIdAndEmployee_EmployeeId(id,employeeId);
        if (wfhTracker == null) { 
            throw new RuntimeException("WFH Tracker not found for the given ID and employee");
        }

        return wfhTracker;
    }

    public WFHTracker getWFHByDate(String employeeId, LocalDate date) {
        WFHTracker wfhTrackers = wfhRepository.findByEmployeeIdAndDate(employeeId,date);
        return wfhTrackers;
    }

}
