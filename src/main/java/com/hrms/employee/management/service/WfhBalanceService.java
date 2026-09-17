package com.hrms.employee.management.service;

import java.util.List;

import com.hrms.employee.management.dto.WfhBalanceDto;
import com.hrms.employee.management.dto.WfhType;

public interface WfhBalanceService {

      public void deductWfhBalance(String employeeId, Long wfhTrackerId);

      public void disburseWfhBalance(Long employeeId, Long wfhTrackerId);

      public List<WfhBalanceDto> getEmployeeWfhBalances(String employeeId);

      public void initializeWfhBalanceForNewEmployee(String employeeId);

      public void initializeWfhBalanceForNewWfhType(WfhType wfhType);

}
