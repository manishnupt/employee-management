package com.hrms.employee.management.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hrms.employee.management.dao.EmployeeWfhBalance;

public interface EmployeeWfhBalanceRepository extends JpaRepository<EmployeeWfhBalance, Long> {
    EmployeeWfhBalance findByEmployeeIdAndWfhTypeName(String employeeId, String wfhTypeName);

    List<EmployeeWfhBalance> findByEmployeeId(String employeeId);
    List<EmployeeWfhBalance> findByEmployeeIdAndYearAndIsActiveTrue(String employeeId, int year);
    List<EmployeeWfhBalance> findByWfhTypeNameAndYearAndIsActiveTrue(String wfhTypeName, int year);

}
