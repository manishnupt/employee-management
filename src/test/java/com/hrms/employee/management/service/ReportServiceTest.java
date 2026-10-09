package com.hrms.employee.management.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dto.AttendanceReportDto;
import com.hrms.employee.management.dto.AttendanceReportDto.DailyRecord;
import com.hrms.employee.management.dto.AttendanceReportDto.DayStatus;
import com.hrms.employee.management.dto.RegularizationDto;
import com.hrms.employee.management.dto.TimesheetDto;
import com.hrms.employee.management.repository.EmployeeRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

    private static final String EMPLOYEE_ID = "e1";
    // Monday to Friday, well in the past.
    private static final LocalDate START = LocalDate.of(2025, 10, 6);
    private static final LocalDate END = LocalDate.of(2025, 10, 10);

    @Mock private TimesheetService timesheetService;
    @Mock private LeaveTrackerService leaveTrackerService;
    @Mock private WFHService wfhService;
    @Mock private RegularizationService regularizationService;
    @Mock private EmployeeRepository employeeRepository;

    @InjectMocks private ReportService service;

    @BeforeEach
    void setUp() {
        Employee employee = new Employee();
        employee.setEmployeeId(EMPLOYEE_ID);
        when(employeeRepository.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(timesheetService.getTimesheetReportByEmployeeId(EMPLOYEE_ID, START, END)).thenReturn(List.of());
        when(leaveTrackerService.getLeavesReportByEmployeeId(EMPLOYEE_ID, START, END)).thenReturn(List.of());
        when(wfhService.getWfhReportByEmployeeId(EMPLOYEE_ID, START, END)).thenReturn(List.of());
        when(regularizationService.getRegularizationReportByEmployeeId(EMPLOYEE_ID, START, END)).thenReturn(List.of());
    }

    @Test
    void pendingRegularizationIsShownOnAnAbsentDayAndCountedAsPending() {
        when(regularizationService.getRegularizationReportByEmployeeId(EMPLOYEE_ID, START, END))
                .thenReturn(List.of(regularization(11L, START, "PENDING")));

        AttendanceReportDto report = service.generateReportByEmployeeAndDateRange(EMPLOYEE_ID, START, END);

        DailyRecord day = report.getDays().get(0);
        assertEquals(DayStatus.ABSENT, day.getStatus());
        assertEquals(11L, day.getRegularization().getId());
        assertFalse(day.getRegularization().isApproved());
        assertEquals(570L, day.getRegularization().getRequestedMinutes());
        assertTrue(day.getRemarks().contains("Regularization request pending approval"));
        assertNull(report.getDays().get(1).getRegularization());

        assertEquals(1, report.getSummary().getPendingRegularizationDays());
        assertEquals(1, report.getSummary().getPendingRequestDays());
        assertEquals(0, report.getSummary().getRegularizedDays());
        assertEquals(5, report.getSummary().getAbsentDays());
    }

    @Test
    void approvedRegularizationMakesTheDayPresentAndRegularized() {
        TimesheetDto timesheet = new TimesheetDto();
        timesheet.setTimesheetId(3L);
        timesheet.setWorkDate(START);
        timesheet.setClockIn(LocalTime.of(9, 0));
        timesheet.setClockOut(LocalTime.of(18, 30));
        timesheet.setStatus("APPROVED");
        timesheet.setIsRegularised(true);
        when(timesheetService.getTimesheetReportByEmployeeId(EMPLOYEE_ID, START, END)).thenReturn(List.of(timesheet));
        when(regularizationService.getRegularizationReportByEmployeeId(EMPLOYEE_ID, START, END))
                .thenReturn(List.of(regularization(11L, START, "APPROVED")));

        AttendanceReportDto report = service.generateReportByEmployeeAndDateRange(EMPLOYEE_ID, START, END);

        DailyRecord day = report.getDays().get(0);
        assertEquals(DayStatus.PRESENT, day.getStatus());
        assertTrue(day.getTimesheet().isRegularised());
        assertTrue(day.getRegularization().isApproved());
        assertNull(day.getRemarks());

        assertEquals(1, report.getSummary().getRegularizedDays());
        assertEquals(0, report.getSummary().getPendingRegularizationDays());
        assertEquals(0, report.getSummary().getPendingRequestDays());
        assertEquals(570L, report.getSummary().getTotalWorkedMinutes());
    }

    @Test
    void rejectedRegularizationIsLeftOutAndAReRaisedOneIsShown() {
        when(regularizationService.getRegularizationReportByEmployeeId(EMPLOYEE_ID, START, END))
                .thenReturn(List.of(regularization(11L, START, "REJECTED"), regularization(12L, START, "PENDING"),
                        regularization(13L, START.plusDays(1), "REJECTED")));

        AttendanceReportDto report = service.generateReportByEmployeeAndDateRange(EMPLOYEE_ID, START, END);

        assertEquals(12L, report.getDays().get(0).getRegularization().getId());
        assertNull(report.getDays().get(1).getRegularization());
        assertNull(report.getDays().get(1).getRemarks());
        assertEquals(1, report.getSummary().getPendingRegularizationDays());
    }

    private static RegularizationDto regularization(Long id, LocalDate workDate, String status) {
        RegularizationDto dto = new RegularizationDto();
        dto.setRegularizationId(id);
        dto.setEmployeeId(EMPLOYEE_ID);
        dto.setWorkDate(workDate);
        dto.setClockIn(LocalTime.of(9, 0));
        dto.setClockOut(LocalTime.of(18, 30));
        dto.setReason("Forgot to punch");
        dto.setStatus(status);
        return dto;
    }
}
