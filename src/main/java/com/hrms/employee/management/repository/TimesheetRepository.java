package com.hrms.employee.management.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.hrms.employee.management.dto.TimesheetDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hrms.employee.management.dao.Timesheet;

public interface TimesheetRepository extends JpaRepository<Timesheet, Long> {
    List<Timesheet> findByEmployee_EmployeeId(String employeeId);

    @Query("SELECT t FROM Timesheet t WHERE EXTRACT(MONTH FROM t.workDate) = :month AND EXTRACT(YEAR FROM t.workDate) = :year AND t.employee.employeeId = :employeeId")
    List<Timesheet> findByEmployeeAndMonth(@Param("employeeId") String employeeId, @Param("month") int month,
            @Param("year") int year);

    Timesheet findByworkDateAndEmployee_EmployeeId(LocalDate WorkDate, String employeeId);

    @Query("SELECT t FROM Timesheet t WHERE t.workDate = :date AND t.employee.employeeId = :id")
    Optional<Timesheet> findByEmployeeIdAndWorkDaate(String id, LocalDate date);
}
