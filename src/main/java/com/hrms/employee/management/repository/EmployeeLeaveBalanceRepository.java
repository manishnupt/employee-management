package com.hrms.employee.management.repository;

import com.hrms.employee.management.dao.EmployeeLeaveBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * Same row as findByEmployeeIdAndLeaveTypeNameAndYearAndIsActiveTrue, locked until the transaction
     * ends, so two approvals for one employee can't both read the same balance and overdraw it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT elb FROM EmployeeLeaveBalance elb WHERE elb.employeeId = :employeeId "
            + "AND elb.leaveTypeName = :leaveTypeName AND elb.year = :year AND elb.isActive = true")
    Optional<EmployeeLeaveBalance> findActiveForUpdate(@Param("employeeId") String employeeId,
                                                       @Param("leaveTypeName") String leaveTypeName,
                                                       @Param("year") int year);

    List<EmployeeLeaveBalance> findByYearLessThanAndIsActiveTrueOrderByYearAsc(int year);
    @Query("SELECT elb FROM EmployeeLeaveBalance elb WHERE elb.year = :year AND elb.isActive = true")
    List<EmployeeLeaveBalance> findAllByYearAndIsActiveTrue(@Param("year") int year);
}