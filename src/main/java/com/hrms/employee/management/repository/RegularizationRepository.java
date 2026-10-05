package com.hrms.employee.management.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hrms.employee.management.dao.Regularization;

public interface RegularizationRepository extends JpaRepository<Regularization, Long> {

    List<Regularization> findByEmployee_EmployeeId(String employeeId);

    /** Pending regularizations with a work date in [startDate, endDate]; status is matched case-insensitively. */
    @Query("SELECT r FROM Regularization r WHERE r.employee.employeeId = :employeeId AND UPPER(r.status) = 'PENDING' AND r.workDate BETWEEN :startDate AND :endDate ORDER BY r.workDate")
    List<Regularization> findPendingInRange(String employeeId, LocalDate startDate, LocalDate endDate);

    /** Pending regularizations that have no action item yet, e.g. raised before a manager was assigned. */
    @Query("SELECT r FROM Regularization r WHERE r.employee.employeeId = :employeeId AND UPPER(r.status) = 'PENDING' AND r.linkedActionItemId IS NULL")
    List<Regularization> findPendingWithoutActionItem(String employeeId);
}
