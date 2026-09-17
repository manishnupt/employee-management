package com.hrms.employee.management.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.hrms.employee.management.dao.EmployeeWfhBalance;

import java.util.List;


@Repository
public interface EmployeeWfhRepository extends JpaRepository<EmployeeWfhBalance, Long> {

    EmployeeWfhBalance findByEmployeeId(String employeeId);
}
