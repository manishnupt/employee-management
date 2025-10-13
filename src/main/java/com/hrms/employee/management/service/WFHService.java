package com.hrms.employee.management.service;

import java.time.LocalDate;
import java.util.List;

import com.hrms.employee.management.dao.WFHTracker;
import com.hrms.employee.management.dto.WFHTrackerRequest;


public interface WFHService {

    WFHTracker applyWFH(String employeeId, WFHTrackerRequest wfhTrackerRequest);
    List<WFHTracker> getWFHHistory(String employeeId);
    WFHTracker getWFHDetailsById(String employeeId, Long id);
    WFHTracker getWFHByDate(String employeeId, LocalDate date);


}
