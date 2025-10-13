package com.hrms.employee.management.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hrms.employee.management.dao.EmployeeWfhBalance;

public interface EmployeeWfhBalanceRepository extends JpaRepository<EmployeeWfhBalance, Long> {
    EmployeeWfhBalance findByEmployeeIdAndWfhTypeName(String employeeId, String wfhTypeName);

}
