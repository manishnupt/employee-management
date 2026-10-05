package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.util.List;

import com.hrms.employee.management.dto.WFHTrackerResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.dto.WFHTrackerRequest;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTrackerRepository;
import com.hrms.employee.management.repository.WFHTrackerRepository;

@Log4j2
@Service
public class WFHSeriveImpl implements WFHService {

    private final WFHTrackerRepository wfhRepository;
    private final EmployeeRepository employeeRepository;
    private ActionItemService actionItemService;

    @Autowired
    private WfhBalanceService wfhBalanceService;

    @Autowired
    private LeaveTrackerRepository leaveTrackerRepository;

    public WFHSeriveImpl(WFHTrackerRepository wfhRepository,EmployeeRepository employeeRepository,
            ActionItemService actionItemService) {
        this.actionItemService = actionItemService;
        this.wfhRepository = wfhRepository;
        this.employeeRepository = employeeRepository;
    }

    public WFHTracker applyWFH(String employeeId, WFHTrackerRequest workFromHomeRequest) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        validateNoApprovedLeave(employeeId, workFromHomeRequest.getStartDate(), workFromHomeRequest.getEndDate());

        WFHTracker workFromHome = WFHTracker.builder()
                .startDate(workFromHomeRequest.getStartDate())
                .endDate(workFromHomeRequest.getEndDate())
                .reason(workFromHomeRequest.getReason())
                .status("PENDING")
                .deductWfhBalance(workFromHomeRequest.isDeductWfhBalance())
                .build();
        workFromHome.setEmployee(employee);
        log.info("Creating action item for WFH request");
        WFHTracker wfhTracker = wfhRepository.save(workFromHome);


        Long actionItemId=actionItemService.createActionItem(employeeId,wfhTracker,employee.getAssignedManagerId());
        if(actionItemId!=null){
            log.info("Action item created successfully with ID: {}", actionItemId);
            wfhTracker.setLinkedActionItemId(actionItemId);
            wfhRepository.save(wfhTracker);
        } else {
            log.warn("Action item creation failed for WFH request of employeeId: {}", employeeId);
        }

        return wfhTracker;
    }

    /** WFH cannot be requested for a range that includes a day already covered by an approved leave. */
    private void validateNoApprovedLeave(String employeeId, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new BusinessException("WFH end date must be on or after the start date");
        }
        List<LeaveTracker> approvedLeaves = leaveTrackerRepository.findApprovedOverlappingRange(employeeId, startDate, endDate);
        if (!approvedLeaves.isEmpty()) {
            LeaveTracker leave = approvedLeaves.get(0);
            log.warn("Rejected WFH request for employee {} from {} to {}: approved leave {} from {} to {}",
                    employeeId, startDate, endDate, leave.getId(), leave.getStartDate(), leave.getEndDate());
            throw new BusinessException(String.format(
                    "Cannot raise a WFH request from %s to %s. Leave is already approved from %s to %s.",
                    startDate, endDate, leave.getStartDate(), leave.getEndDate()));
        }
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

    @Override
    public List<WFHTrackerResponse> getWfhReportByEmployeeId(String employeeId, LocalDate startDate, LocalDate endDate) {
        List<WFHTracker> wfhTracker= wfhRepository.findOverlappingRange(employeeId, startDate, endDate);
        return wfhTracker.stream().map(wfh -> new WFHTrackerResponse(
                wfh.getId(),
                wfh.getStartDate(),
                wfh.getEndDate(),
                wfh.getReason(),
                wfh.getStatus()
        )).toList();
    }

    @Override
    public List<WFHTracker> getUnassignedWfhs(String employeeId) {
        return wfhRepository.findByEmployee_EmployeeIdAndLinkedActionItemIdIsNull(employeeId);
    }

    @Override
    public void saveLinkedActionItemId(Long id, Long actionItem) {
        wfhRepository.findById(id).ifPresent(wfh -> {
            wfh.setLinkedActionItemId(actionItem);
            wfhRepository.save(wfh);
        });

    }

    @Override
    public WFHTracker updateWFHStatus(String employeeId, Long id, String status) {
        WFHTracker wfhTracker = wfhRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("WFH Tracker not found"));

        if (!wfhTracker.getEmployee().getEmployeeId().equals(employeeId)) {
            throw new RuntimeException("WFH Tracker does not belong to the specified employee");
        }
        if(status.equalsIgnoreCase("APPROVED"))
        {
            wfhBalanceService.deductWfhBalance(employeeId, id);
        }

        wfhTracker.setStatus(status);
        return wfhRepository.save(wfhTracker);
    }

    /** A WFH request can be withdrawn only while it is still awaiting a decision. */
    @Override
    @Transactional
    public void deleteWFH(String employeeId, Long id) {
        WFHTracker wfhTracker = wfhRepository.findById(id)
                .orElseThrow(() -> new BusinessException("WFH request not found"));

        if (!wfhTracker.getEmployee().getEmployeeId().equals(employeeId)) {
            throw new BusinessException("WFH request does not belong to the specified employee");
        }
        if (!"PENDING".equalsIgnoreCase(wfhTracker.getStatus())) {
            throw new BusinessException("WFH request is already " + wfhTracker.getStatus()
                    + " and cannot be deleted. Only a pending WFH request can be deleted.");
        }
        wfhRepository.delete(wfhTracker);
        actionItemService.deleteActionItem(wfhTracker.getLinkedActionItemId());
        log.info("Pending WFH {} deleted for employee {}", id, employeeId);
    }

}
