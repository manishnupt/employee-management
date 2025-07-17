package com.hrms.employee.management.service;

import java.util.List;

import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dto.LeaveTrackerDto;
import com.hrms.employee.management.dto.LeaveTrackerResponse;

public interface LeaveTrackerService {
    LeaveTrackerResponse applyLeave(String employeeId, LeaveTrackerDto leaveTrackerDto);
    List<LeaveTracker> getLeaveHistory(String employeeId);
    LeaveTracker getLeaveById(Long id);
}
