package com.hrms.employee.management.repository;

import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeLeaveBalanceRepository extends JpaRepository<EmployeeLeaveBalance, Long> {
    List<EmployeeLeaveBalance> findByEmployeeIdAndIsActiveTrue(String employeeId);
    List<EmployeeLeaveBalance> findByEmployeeIdAndYearAndIsActiveTrue(String employeeId, int year);
    // Optional<EmployeeLeaveBalance> findByEmployeeIdAndLeaveTypeIdAndYearAndIsActiveTrue(String employeeId, String leaveTypeId, int year);
    // List<EmployeeLeaveBalance> findByLeaveTypeIdAndIsActiveTrue(String leaveTypeId);

    Optional<EmployeeLeaveBalance> findByEmployeeIdAndLeaveTypeName(String employeeId, String leaveTypeName);

    Optional<EmployeeLeaveBalance> findByEmployeeIdAndLeaveTypeNameAndYearAndIsActiveTrue(String employeeId, String leaveTypeName, int year);

    List<EmployeeLeaveBalance> findByLeaveTypeNameAndYearAndIsActiveTrue(String leaveTypeName, int year);

    List<EmployeeLeaveBalance> findByYearLessThanAndIsActiveTrueOrderByYearAsc(int year);
    @Query("SELECT elb FROM EmployeeLeaveBalance elb WHERE elb.year = :year AND elb.isActive = true")
    List<EmployeeLeaveBalance> findAllByYearAndIsActiveTrue(@Param("year") int year);
}