package com.hrms.employee.management.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.LeaveTracker;
import com.hrms.employee.management.dao.Regularization;
import com.hrms.employee.management.dao.Timesheet;
import com.hrms.employee.management.dto.RegularizationDto;
import com.hrms.employee.management.exceptions.BusinessException;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.LeaveTrackerRepository;
import com.hrms.employee.management.repository.RegularizationRepository;
import com.hrms.employee.management.repository.TimesheetRepository;
import com.hrms.employee.management.utility.TimesheetUtil;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegularizationServiceImplTest {

    private static final String EMPLOYEE_ID = "e1";
    private static final String MANAGER_ID = "m1";
    private static final Long REGULARIZATION_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 10, 12);

    @Mock private RegularizationRepository regularizationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private LeaveTrackerRepository leaveTrackerRepository;
    @Mock private TimesheetRepository timesheetRepository;
    @Mock private ActionItemService actionItemService;

    private RegularizationServiceImpl service;
    private Employee employee;
    private Regularization regularization;

    @BeforeEach
    void setUp() {
        service = new RegularizationServiceImpl(regularizationRepository, employeeRepository, leaveTrackerRepository,
                timesheetRepository, actionItemService);
        ReflectionTestUtils.setField(service, "istClock",
                Clock.fixed(TODAY.atTime(11, 0).atZone(TimesheetUtil.IST).toInstant(), TimesheetUtil.IST));

        employee = new Employee();
        employee.setEmployeeId(EMPLOYEE_ID);
        employee.setAssignedManagerId(MANAGER_ID);
        when(employeeRepository.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        regularization = new Regularization();
        regularization.setId(REGULARIZATION_ID);
        regularization.setEmployee(employee);
        regularization.setWorkDate(WORK_DATE);
        regularization.setClockIn(LocalTime.of(9, 0));
        regularization.setClockOut(LocalTime.of(18, 30));
        regularization.setStatus("PENDING");
        when(regularizationRepository.findById(REGULARIZATION_ID)).thenReturn(Optional.of(regularization));
        when(regularizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(timesheetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void raiseSavesPendingRequestAndLinksActionItem() {
        when(actionItemService.createActionItem(eq(EMPLOYEE_ID), any(Regularization.class), eq(MANAGER_ID)))
                .thenReturn(99L);

        RegularizationDto result = service.raiseRegularization(EMPLOYEE_ID, request(WORK_DATE));

        assertEquals("PENDING", result.getStatus());
        ArgumentCaptor<Regularization> saved = ArgumentCaptor.forClass(Regularization.class);
        verify(regularizationRepository, atLeastOnce()).save(saved.capture());
        assertEquals(99L, saved.getValue().getLinkedActionItemId());
        verify(timesheetRepository, never()).save(any());
    }

    @Test
    void raiseRefusesTodayAndFutureDays() {
        assertThrows(BusinessException.class, () -> service.raiseRegularization(EMPLOYEE_ID, request(TODAY)));
        assertThrows(BusinessException.class,
                () -> service.raiseRegularization(EMPLOYEE_ID, request(TODAY.plusDays(1))));

        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void raiseRefusesDaysBeforeTheCurrentMonth() {
        assertThrows(BusinessException.class,
                () -> service.raiseRegularization(EMPLOYEE_ID, request(LocalDate.of(2026, 9, 30))));

        service.raiseRegularization(EMPLOYEE_ID, request(LocalDate.of(2026, 10, 1)));
        verify(regularizationRepository, atLeastOnce()).save(any());
    }

    @Test
    void raiseRefusesDayWithPendingOrApprovedLeave() {
        LeaveTracker leave = new LeaveTracker();
        leave.setStartDate(WORK_DATE);
        leave.setEndDate(WORK_DATE);
        leave.setStatus("Pending");
        when(leaveTrackerRepository.findPendingOrApprovedOverlappingRange(EMPLOYEE_ID, WORK_DATE, WORK_DATE))
                .thenReturn(List.of(leave));

        assertThrows(BusinessException.class, () -> service.raiseRegularization(EMPLOYEE_ID, request(WORK_DATE)));

        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void raiseRefusesDayWithRegularizationAlreadyPending() {
        when(regularizationRepository.findPendingInRange(EMPLOYEE_ID, WORK_DATE, WORK_DATE))
                .thenReturn(List.of(regularization));

        assertThrows(BusinessException.class, () -> service.raiseRegularization(EMPLOYEE_ID, request(WORK_DATE)));

        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void raiseRefusesDayWithFilledTimesheetButAllowsMissedClockOut() {
        Timesheet timesheet = new Timesheet();
        timesheet.setClockIn(LocalTime.of(9, 0));
        when(timesheetRepository.findByworkDateAndEmployee_EmployeeId(WORK_DATE, EMPLOYEE_ID)).thenReturn(timesheet);

        service.raiseRegularization(EMPLOYEE_ID, request(WORK_DATE));
        verify(regularizationRepository, atLeastOnce()).save(any());

        timesheet.setClockOut(LocalTime.of(18, 0));
        assertThrows(BusinessException.class, () -> service.raiseRegularization(EMPLOYEE_ID, request(WORK_DATE)));
    }

    @Test
    void raiseRefusesMissingOrReversedTimes() {
        RegularizationDto missingClockOut = request(WORK_DATE);
        missingClockOut.setClockOut(null);
        RegularizationDto reversed = request(WORK_DATE);
        reversed.setClockOut(LocalTime.of(8, 0));

        assertThrows(BusinessException.class, () -> service.raiseRegularization(EMPLOYEE_ID, missingClockOut));
        assertThrows(BusinessException.class, () -> service.raiseRegularization(EMPLOYEE_ID, reversed));
    }

    @Test
    void approvingCreatesRegularisedApprovedTimesheet() {
        RegularizationDto result = service.updateRegularizationStatus(EMPLOYEE_ID, REGULARIZATION_ID, "approved");

        assertEquals("APPROVED", result.getStatus());
        ArgumentCaptor<Timesheet> saved = ArgumentCaptor.forClass(Timesheet.class);
        verify(timesheetRepository).save(saved.capture());
        Timesheet timesheet = saved.getValue();
        assertEquals(Boolean.TRUE, timesheet.getIsRegularised());
        assertEquals("APPROVED", timesheet.getStatus());
        assertEquals(WORK_DATE, timesheet.getWorkDate());
        assertEquals(LocalTime.of(9, 0), timesheet.getClockIn());
        assertEquals(LocalTime.of(18, 30), timesheet.getClockOut());
        assertEquals(9.5, timesheet.getTotalHours());
        assertSame(employee, timesheet.getEmployee());
    }

    @Test
    void approvingCompletesExistingTimesheetRowInsteadOfDuplicating() {
        Timesheet existing = new Timesheet();
        existing.setId(5L);
        existing.setEmployee(employee);
        existing.setWorkDate(WORK_DATE);
        existing.setClockIn(LocalTime.of(10, 0));
        existing.setStatus("CLOCK_OUT_PENDING");
        when(timesheetRepository.findByworkDateAndEmployee_EmployeeId(WORK_DATE, EMPLOYEE_ID)).thenReturn(existing);

        service.updateRegularizationStatus(EMPLOYEE_ID, REGULARIZATION_ID, "APPROVED");

        verify(timesheetRepository).save(existing);
        assertEquals(LocalTime.of(9, 0), existing.getClockIn());
        assertEquals("APPROVED", existing.getStatus());
        assertEquals(Boolean.TRUE, existing.getIsRegularised());
    }

    @Test
    void rejectingOnlyChangesStatus() {
        RegularizationDto result = service.updateRegularizationStatus(EMPLOYEE_ID, REGULARIZATION_ID, "REJECTED");

        assertEquals("REJECTED", result.getStatus());
        verify(timesheetRepository, never()).save(any());
    }

    @Test
    void repeatedApprovalDoesNotWriteTimesheetAgain() {
        regularization.setStatus("APPROVED");

        service.updateRegularizationStatus(EMPLOYEE_ID, REGULARIZATION_ID, "APPROVED");

        verify(timesheetRepository, never()).save(any());
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void decidedRegularizationCannotBeChanged() {
        regularization.setStatus("REJECTED");

        assertThrows(BusinessException.class,
                () -> service.updateRegularizationStatus(EMPLOYEE_ID, REGULARIZATION_ID, "APPROVED"));

        verify(timesheetRepository, never()).save(any());
    }

    @Test
    void unknownStatusAndOtherEmployeeAreRefused() {
        assertThrows(BusinessException.class,
                () -> service.updateRegularizationStatus(EMPLOYEE_ID, REGULARIZATION_ID, "PENDING"));
        assertThrows(BusinessException.class,
                () -> service.updateRegularizationStatus("someone-else", REGULARIZATION_ID, "APPROVED"));

        verify(timesheetRepository, never()).save(any());
    }

    private static RegularizationDto request(LocalDate workDate) {
        RegularizationDto dto = new RegularizationDto();
        dto.setWorkDate(workDate);
        dto.setClockIn(LocalTime.of(9, 0));
        dto.setClockOut(LocalTime.of(18, 30));
        dto.setReason("Forgot to clock in");
        return dto;
    }

    @Test
    void deletingPendingRegularizationRemovesIt() {
        regularization.setLinkedActionItemId(99L);

        service.deleteRegularization(EMPLOYEE_ID, REGULARIZATION_ID);

        verify(regularizationRepository).delete(regularization);
        verify(actionItemService).deleteActionItem(99L);
    }

    @Test
    void deletingDecidedRegularizationIsRejected() {
        regularization.setStatus("REJECTED");

        assertThrows(BusinessException.class, () -> service.deleteRegularization(EMPLOYEE_ID, REGULARIZATION_ID));
        verify(regularizationRepository, never()).delete(any());
        verify(actionItemService, never()).deleteActionItem(any());
    }
}
