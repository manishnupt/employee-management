package com.hrms.employee.management.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.hrms.employee.management.dao.Employee;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, String>, JpaSpecificationExecutor<Employee> {

	long countByJobStatus(String jobStatus);
	List<Employee> findByGroupIdIsNull();

	List<Employee> findByGroupId(Long groupId);

	Optional<Employee> findByKcReferenceId(String kcRefId);
}
