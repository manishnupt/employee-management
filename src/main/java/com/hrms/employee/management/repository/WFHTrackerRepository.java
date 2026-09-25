package com.hrms.employee.management.repository;

import java.time.LocalDate;
import java.util.List;

import com.hrms.employee.management.dto.WFHTrackerResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.hrms.employee.management.dao.WFHTracker;

@Repository
public interface WFHTrackerRepository extends JpaRepository<WFHTracker, Long> {

    List<WFHTracker> findAllByEmployee_EmployeeId(String employeeId);
    WFHTracker findByIdAndEmployee_EmployeeId(Long id, String employeeId);

    @Query("SELECT w FROM WFHTracker w WHERE w.employee.employeeId = :employeeId AND w.startDate <= :date AND w.endDate >= :date")
    WFHTracker findByEmployeeIdAndDate(String employeeId, LocalDate date);

    /** WFH requests that overlap [startDate, endDate], including ones that start before or end after the range. */
    @Query("SELECT w FROM WFHTracker w WHERE w.employee.employeeId = :employeeId AND w.startDate <= :endDate AND w.endDate >= :startDate")
    List<WFHTracker> findOverlappingRange(String employeeId, LocalDate startDate, LocalDate endDate);

    List<WFHTracker> findByEmployee_EmployeeIdAndLinkedActionItemIdIsNull(String employeeId);
}
