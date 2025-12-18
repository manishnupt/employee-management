package com.hrms.employee.management.service;

public interface WfhBalanceService {

      public void deductWfhBalance(Long employeeId, Long wfhTrackerId);

      public void disburseWfhBalance(Long employeeId, Long wfhTrackerId);

}
