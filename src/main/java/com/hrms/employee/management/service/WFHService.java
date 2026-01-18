package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.util.List;

import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.dto.WFHTrackerRequest;
import com.hrms.employee.management.dto.WFHTrackerResponse;


public interface WFHService {

    WFHTracker applyWFH(String employeeId, WFHTrackerRequest wfhTrackerRequest);
    List<WFHTracker> getWFHHistory(String employeeId);
    WFHTracker getWFHDetailsById(String employeeId, Long id);
    WFHTracker getWFHByDate(String employeeId, LocalDate date);


    List<WFHTrackerResponse> getWfhReportByEmployeeId(String employeeId, LocalDate startDate, LocalDate endDate);
}
