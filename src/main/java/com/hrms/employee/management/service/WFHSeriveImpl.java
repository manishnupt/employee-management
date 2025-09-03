package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.dto.WFHTrackerRequest;
import com.hrms.employee.management.dto.WFHTrackerResponse;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.WFHTrackerRepository;

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

    public WFHTrackerResponse applyWFH(String employeeId, WFHTrackerRequest workFromHomeRequest) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        WFHTracker workFromHome = WFHTracker.builder()
                .startDate(workFromHomeRequest.getStartDate())
                .endDate(workFromHomeRequest.getEndDate())
                .reason(workFromHomeRequest.getReason())
                .status("PENDING")
                .build();
        workFromHome.setEmployee(employee);

        WFHTracker wfhTracker = wfhRepository.save(workFromHome);

        actionItemService.createActionItem(employeeId, wfhTracker, employee.getAssignedManagerId());
        return convertToResponse(workFromHome); 
    }

    public List<WFHTrackerResponse> getWFHHistory(String employeeId) {

        List<WFHTracker> wfhTrackers = wfhRepository.findAllByEmployee_EmployeeId(employeeId);
        return wfhTrackers.stream()
                .map(this::convertToResponse)
                .toList();
    }

    public WFHTrackerResponse getWFHDetailsById(String employeeId, Long id) {
        WFHTracker wfhTracker = wfhRepository.findByIdAndEmployee_EmployeeId(id,employeeId);

        if (wfhTracker == null) { 
            throw new RuntimeException("WFH Tracker not found for the given ID and employee");
        }

        return convertToResponse(wfhTracker);
    }

    public WFHTrackerResponse getWFHByDate(String employeeId, LocalDate date) {
        WFHTracker wfhTrackers = wfhRepository.findByEmployeeIdAndDate(employeeId,date);
        return convertToResponse(wfhTrackers);
    }

    public WFHTrackerResponse convertToResponse(WFHTracker wfhTracker) {
        return WFHTrackerResponse.builder()
                .id(wfhTracker.getId())
                .startDate(wfhTracker.getStartDate())
                .endDate(wfhTracker.getEndDate())
                .reason(wfhTracker.getReason())
                .status(wfhTracker.getStatus())
                .build();
    }
}
