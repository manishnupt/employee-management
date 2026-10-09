package com.hrms.employee.management.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class RegularizationServiceImpl implements RegularizationService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";

    private Clock istClock = Clock.system(TimesheetUtil.IST);

    private final RegularizationRepository regularizationRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveTrackerRepository leaveTrackerRepository;
    private final TimesheetRepository timesheetRepository;
    private final ActionItemService actionItemService;

    public RegularizationServiceImpl(RegularizationRepository regularizationRepository,
            EmployeeRepository employeeRepository, LeaveTrackerRepository leaveTrackerRepository,
            TimesheetRepository timesheetRepository, ActionItemService actionItemService) {
        this.regularizationRepository = regularizationRepository;
        this.employeeRepository = employeeRepository;
        this.leaveTrackerRepository = leaveTrackerRepository;
        this.timesheetRepository = timesheetRepository;
        this.actionItemService = actionItemService;
    }

    @Override
    @Transactional
    public RegularizationDto raiseRegularization(String employeeId, RegularizationDto regularizationDto) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new BusinessException("Employee not found"));

        LocalDate workDate = regularizationDto.getWorkDate();
        validateTimes(regularizationDto);
        validateWorkDate(employee, workDate);
        validateNoPendingOrApprovedLeave(employeeId, workDate);
        validateNoPendingRegularization(employeeId, workDate);
        validateTimesheetMissed(employeeId, workDate);

        Regularization regularization = new Regularization();
        regularization.setEmployee(employee);
        regularization.setWorkDate(workDate);
        regularization.setClockIn(regularizationDto.getClockIn());
        regularization.setClockOut(regularizationDto.getClockOut());
        regularization.setReason(regularizationDto.getReason());
        regularization.setStatus(STATUS_PENDING);
        regularization = regularizationRepository.save(regularization);
        log.info("Regularization {} raised for employee {} on {}", regularization.getId(), employeeId, workDate);

        Long actionItemId = actionItemService.createActionItem(employeeId, regularization,
                employee.getAssignedManagerId());
        if (actionItemId != null) {
            regularization.setLinkedActionItemId(actionItemId);
            regularization = regularizationRepository.save(regularization);
        }

        return convertToDto(regularization);
    }

    private void validateTimes(RegularizationDto regularizationDto) {
        if (regularizationDto.getClockIn() == null || regularizationDto.getClockOut() == null) {
            throw new BusinessException("Both clockIn and clockOut are required to raise a regularization.");
        }
        if (!regularizationDto.getClockOut().isAfter(regularizationDto.getClockIn())) {
            throw new BusinessException("Clock-out time must be after the clock-in time.");
        }
    }

    /** Regularization is only for past days of the current month (IST), from the onboarding date onwards. */
    private void validateWorkDate(Employee employee, LocalDate workDate) {
        if (workDate == null) {
            throw new BusinessException("Work date is required to raise a regularization.");
        }
        LocalDate today = LocalDate.now(istClock);
        if (!workDate.isBefore(today)) {
            throw new BusinessException(String.format(
                    "Regularization can only be raised for past days; received work date %s (today is %s IST).",
                    workDate, today));
        }
        if (workDate.isBefore(today.withDayOfMonth(1))) {
            throw new BusinessException(String.format(
                    "Regularization can only be raised for days within the current month; received work date %s.",
                    workDate));
        }
        if (employee.getCreatedAt() != null && workDate.isBefore(employee.getCreatedAt().toLocalDate())) {
            throw new BusinessException(String.format(
                    "Cannot raise a regularization for %s. Employee was onboarded on %s.",
                    workDate, employee.getCreatedAt().toLocalDate()));
        }
    }

    /** A pending or approved WFH does not block a regularization, but a pending or approved leave does. */
    private void validateNoPendingOrApprovedLeave(String employeeId, LocalDate workDate) {
        List<LeaveTracker> leaves = leaveTrackerRepository.findPendingOrApprovedOverlappingRange(employeeId, workDate,
                workDate);
        if (!leaves.isEmpty()) {
            LeaveTracker leave = leaves.get(0);
            log.warn("Rejected regularization for employee {} on {}: leave {} is {} from {} to {}",
                    employeeId, workDate, leave.getId(), leave.getStatus(), leave.getStartDate(), leave.getEndDate());
            throw new BusinessException(String.format(
                    "Cannot raise a regularization for %s. Leave from %s to %s is %s.",
                    workDate, leave.getStartDate(), leave.getEndDate(), leave.getStatus().toLowerCase(Locale.ROOT)));
        }
    }

    private void validateNoPendingRegularization(String employeeId, LocalDate workDate) {
        if (!regularizationRepository.findPendingInRange(employeeId, workDate, workDate).isEmpty()) {
            throw new BusinessException(String.format(
                    "A regularization request for %s is already pending approval.", workDate));
        }
    }

    /** A day is "missed" when it has no timesheet, or one that was never clocked out. */
    private void validateTimesheetMissed(String employeeId, LocalDate workDate) {
        Timesheet timesheet = timesheetRepository.findByworkDateAndEmployee_EmployeeId(workDate, employeeId);
        if (timesheet != null && timesheet.getClockOut() != null) {
            throw new BusinessException(String.format(
                    "Cannot raise a regularization for %s. A timesheet is already filled for this day.", workDate));
        }
    }

    @Override
    public List<RegularizationDto> getRegularizationHistory(String employeeId) {
        return regularizationRepository.findByEmployee_EmployeeId(employeeId).stream().map(this::convertToDto).toList();
    }

    @Override
    public List<RegularizationDto> getRegularizationReportByEmployeeId(String employeeId, LocalDate startDate,
            LocalDate endDate) {
        return regularizationRepository.findByEmployee_EmployeeIdAndWorkDateBetween(employeeId, startDate, endDate)
                .stream().map(this::convertToDto).toList();
    }

    @Override
    public RegularizationDto getRegularizationById(String employeeId, Long id) {
        return convertToDto(findForEmployee(employeeId, id));
    }

    @Override
    @Transactional
    public RegularizationDto updateRegularizationStatus(String employeeId, Long id, String status) {
        Regularization regularization = findForEmployee(employeeId, id);

        String newStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_APPROVED.equals(newStatus) && !STATUS_REJECTED.equals(newStatus)) {
            throw new BusinessException("Regularization status must be APPROVED or REJECTED");
        }
        String currentStatus = regularization.getStatus();
        if (newStatus.equalsIgnoreCase(currentStatus)) {
            // Repeat of a decision already recorded (e.g. a retried request): nothing to change.
            log.info("Regularization {} is already {}. Ignoring status update.", id, currentStatus);
            return convertToDto(regularization);
        }
        if (!STATUS_PENDING.equalsIgnoreCase(currentStatus)) {
            throw new BusinessException(
                    "Regularization is already " + currentStatus + " and cannot be changed to " + newStatus);
        }
        // Only an approval produces a timesheet; a rejection just records the decision.
        if (STATUS_APPROVED.equals(newStatus)) {
            createRegularisedTimesheet(regularization);
        }

        regularization.setStatus(newStatus);
        return convertToDto(regularizationRepository.save(regularization));
    }

    /**
     * Writes the regularized times to the day's timesheet as already approved. There is one timesheet row per
     * employee per work date, so a row left without a clock-out is completed rather than duplicated.
     */
    private void createRegularisedTimesheet(Regularization regularization) {
        String employeeId = regularization.getEmployee().getEmployeeId();
        Timesheet timesheet = timesheetRepository.findByworkDateAndEmployee_EmployeeId(regularization.getWorkDate(),
                employeeId);
        if (timesheet == null) {
            timesheet = new Timesheet();
            timesheet.setEmployee(regularization.getEmployee());
            timesheet.setWorkDate(regularization.getWorkDate());
        }
        timesheet.setClockIn(regularization.getClockIn());
        timesheet.setClockOut(regularization.getClockOut());
        Duration duration = Duration.between(regularization.getClockIn(), regularization.getClockOut());
        timesheet.setTotalHours(duration.toHours() + (duration.toMinutesPart() / 60.0));
        timesheet.setStatus(STATUS_APPROVED);
        timesheet.setIsRegularised(true);
        timesheet = timesheetRepository.save(timesheet);
        log.info("Regularization {} approved: timesheet {} recorded for employee {} on {}",
                regularization.getId(), timesheet.getId(), employeeId, regularization.getWorkDate());
    }

    /** A regularization request can be withdrawn only while it is still awaiting a decision. */
    @Override
    @Transactional
    public void deleteRegularization(String employeeId, Long id) {
        Regularization regularization = findForEmployee(employeeId, id);
        if (!STATUS_PENDING.equalsIgnoreCase(regularization.getStatus())) {
            throw new BusinessException("Regularization request is already " + regularization.getStatus()
                    + " and cannot be deleted. Only a pending regularization request can be deleted.");
        }
        regularizationRepository.delete(regularization);
        actionItemService.deleteActionItem(regularization.getLinkedActionItemId());
        log.info("Pending regularization {} deleted for employee {}", id, employeeId);
    }

    @Override
    public List<Regularization> getUnassignedRegularizations(String employeeId) {
        return regularizationRepository.findPendingWithoutActionItem(employeeId);
    }

    @Override
    public void saveLinkedActionItemId(Long id, Long actionItem) {
        regularizationRepository.findById(id).ifPresent(regularization -> {
            regularization.setLinkedActionItemId(actionItem);
            regularizationRepository.save(regularization);
        });
    }

    private Regularization findForEmployee(String employeeId, Long id) {
        Regularization regularization = regularizationRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Regularization not found"));
        if (!regularization.getEmployee().getEmployeeId().equals(employeeId)) {
            throw new BusinessException("Regularization does not belong to the specified employee");
        }
        return regularization;
    }

    private RegularizationDto convertToDto(Regularization regularization) {
        RegularizationDto dto = new RegularizationDto();
        dto.setRegularizationId(regularization.getId());
        dto.setEmployeeId(regularization.getEmployee().getEmployeeId());
        dto.setWorkDate(regularization.getWorkDate());
        dto.setClockIn(regularization.getClockIn());
        dto.setClockOut(regularization.getClockOut());
        dto.setReason(regularization.getReason());
        dto.setStatus(regularization.getStatus());
        return dto;
    }
}
