package com.hrms.employee.management.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.hrms.employee.management.utility.TimesheetUtil;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.hrms.employee.management.dao.Employee;
import com.hrms.employee.management.dao.Timesheet;
import com.hrms.employee.management.dto.TimesheetDto;
import com.hrms.employee.management.repository.EmployeeRepository;
import com.hrms.employee.management.repository.TimesheetRepository;

@Service
@Log4j2
public class TimesheetServiceImpl implements TimesheetService {

    @Autowired
    private TimesheetRepository timesheetRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    ActionItemService actionItemService;

    @Override
    public TimesheetDto logWork(String employeeId, TimesheetDto timesheetDto) {
        // Fetch the employee
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        // Create Timesheet entry
        Timesheet timesheet = new Timesheet();
        timesheet.setEmployee(employee);
        timesheet.setWorkDate(timesheetDto.getWorkDate());
        timesheet.setClockIn(timesheetDto.getClockIn());
        timesheet.setClockOut(timesheetDto.getClockOut());
        timesheet.setStatus("PENDING");
        // Save the entity
        Timesheet savedTimesheet = timesheetRepository.save(timesheet);

        Long actionItemId = null;
        if(employee.getAssignedManagerId()!=null && !employee.getAssignedManagerId().isEmpty() && savedTimesheet.getClockOut() != null) {
            actionItemId = actionItemService.createActionItem(employeeId, savedTimesheet, employee.getAssignedManagerId());
            if(actionItemId != null){
                savedTimesheet.setLinkedActionItemId(actionItemId);
                timesheetRepository.save(savedTimesheet);
            }
        }
        // Convert and return DTO
        return convertToDto(savedTimesheet);
    }

    @Override
    public List<TimesheetDto> getTimesheetByEmployeeId(String employeeId) {
        List<Timesheet> timesheets = timesheetRepository.findByEmployee_EmployeeId(employeeId);
        return timesheets.stream().map(this::convertToDto).collect(Collectors.toList());
    }

     @Override
    public TimesheetDto getTimesheetById(Long id) {
        Timesheet timesheet = timesheetRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Timesheet not found"));
        return convertToDto(timesheet);
    }


    private TimesheetDto convertToDto(Timesheet timesheet) {
        log.info("Converting Timesheet entity to DTO for timesheet ID: {}", timesheet.getId());
        TimesheetDto dto = new TimesheetDto();
        dto.setTimesheetId(timesheet.getId());
        dto.setEmployeeId(timesheet.getEmployee().getEmployeeId());
        dto.setWorkDate(timesheet.getWorkDate());
        dto.setClockIn(timesheet.getClockIn());
        dto.setClockOut(timesheet.getClockOut());
        dto.setTotalHours(TimesheetUtil.calculateWorkedTimeInWords(timesheet.getWorkDate(),timesheet.getWorkDate(),timesheet.getClockIn(),timesheet.getClockOut()));
        return dto;
    }

    @Override
    public TimesheetDto clock(String employeeId, TimesheetDto timesheetDto) {

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        Timesheet timesheet = timesheetRepository.findByworkDateAndEmployee_EmployeeId(
                timesheetDto.getWorkDate(), employeeId);

       
        if (timesheet == null) {
            timesheet= new Timesheet();
            timesheet.setEmployee(employee);
            timesheet.setWorkDate(timesheetDto.getWorkDate());
            timesheet.setClockIn(timesheetDto.getClockIn());
            timesheet.setStatus("CLOCK_OUT_PENDING");
        }
        else{
            if (timesheet.getClockIn() == null) {
                throw new RuntimeException("No clock-in record found for this date.");
            }
            if(timesheet.getClockOut() != null) {
                throw new RuntimeException("Already clocked out for this date.");
            }
            timesheet.setClockOut(timesheetDto.getClockOut());
            Duration duration = Duration.between(timesheet.getClockIn(), timesheetDto.getClockOut());
            timesheet.setTotalHours(duration.toHours() + (duration.toMinutesPart() / 60.0));
            timesheet.setStatus("PENDING");
            
        }
        Timesheet savedTimesheet = timesheetRepository.save(timesheet);
        Long actionItemId = null;
        if(savedTimesheet.getClockOut() != null) {
             actionItemId = actionItemService.createActionItem(employeeId, savedTimesheet, employee.getAssignedManagerId());
        }
        if(actionItemId != null){
            savedTimesheet.setLinkedActionItemId(actionItemId);
            timesheetRepository.save(savedTimesheet);
        }

        return convertToDto(savedTimesheet);
    }

    @Override
    public TimesheetDto getTimesheetByEmployeeIdAndDate(String employeeId, LocalDate date) {
        Optional<Timesheet> byEmployeeIdAndWorkDaate = timesheetRepository.findByEmployeeIdAndWorkDaate(employeeId, date);
        if(byEmployeeIdAndWorkDaate.isPresent())
            return convertToDto(byEmployeeIdAndWorkDaate.get());

        else
            return new TimesheetDto();
    }

    @Override
    public List<TimesheetDto> getTimesheetReportByEmployeeId(String employeeId, LocalDate startDate, LocalDate endDate) {
        List<Timesheet> timesheets = timesheetRepository.findByEmployee_EmployeeIdAndWorkDateBetween(employeeId, startDate, endDate);
        return timesheets.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    @Override
    public TimesheetDto approveTimesheet(String employeeId, Long id) {
        Timesheet timesheet = timesheetRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Timesheet not found"));

        if (!timesheet.getEmployee().getEmployeeId().equals(employeeId)) {
            throw new RuntimeException("Timesheet does not belong to the specified employee");
        }

        timesheet.setStatus("APPROVED");
        Timesheet savedTimesheet = timesheetRepository.save(timesheet);

        return convertToDto(savedTimesheet);

    }

    @Override
    public List<Timesheet> getUnassignedTimesheets(String employeeId) {
        return timesheetRepository.findByEmployee_EmployeeIdAndLinkedActionItemIdIsNull(employeeId);
    }

    @Override
    public void saveLinkedActionItemId(Long id, Long actionItem) {
        Timesheet timesheet = timesheetRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Timesheet not found"));

        timesheet.setLinkedActionItemId(actionItem);
        timesheetRepository.save(timesheet);

    }
}