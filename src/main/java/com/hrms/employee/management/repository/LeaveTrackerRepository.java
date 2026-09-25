package com.hrms.employee.management.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hrms.employee.management.dao.LeaveTracker;

public interface LeaveTrackerRepository extends JpaRepository<LeaveTracker, Long> {
    List<LeaveTracker> findByEmployee_EmployeeId(String employeeId);

    @Query("SELECT l FROM LeaveTracker l WHERE l.startDate <=:endDate AND l.endDate >= :startDate AND l.employee.employeeId = :employeeId")
    List<LeaveTracker> findByEmployeeAndMonth(@Param("employeeId") String employeeId, @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** Leaves that overlap [startDate, endDate], including ones that start before or end after the range. */
    @Query("SELECT l FROM LeaveTracker l WHERE l.employee.employeeId = :employeeId AND l.startDate <= :endDate AND l.endDate >= :startDate")
    List<LeaveTracker> findOverlappingRange(String employeeId, LocalDate startDate, LocalDate endDate);

    List<LeaveTracker> findByEmployee_EmployeeIdAndLinkedActionItemIdIsNull(String employeeId);
}
