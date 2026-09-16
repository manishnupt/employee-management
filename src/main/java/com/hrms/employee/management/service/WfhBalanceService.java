package com.hrms.employee.management.service;

import java.util.List;

import com.hrms.employee.management.dto.WfhBalanceDto;

public interface WfhBalanceService {

      public void deductWfhBalance(Long employeeId, Long wfhTrackerId);

      public void disburseWfhBalance(Long employeeId, Long wfhTrackerId);

      public List<WfhBalanceDto> getEmployeeWfhBalances(String employeeId);

}
