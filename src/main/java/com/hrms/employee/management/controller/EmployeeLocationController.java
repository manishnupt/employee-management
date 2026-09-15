package com.hrms.employee.management.controller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.hrms.employee.management.dao.LocationLog;
import com.hrms.employee.management.dto.EmployeeLocationRequest;
import com.hrms.employee.management.dto.LocationLogResponse;
import com.hrms.employee.management.exceptions.DeviceLocationException;
import com.hrms.employee.management.exceptions.EmployeeNotFoundException;
import com.hrms.employee.management.service.EmployeeLocationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("/api/employee-location")
@RequiredArgsConstructor
@CrossOrigin(origins ="*")
@Log4j2
public class EmployeeLocationController {

    private final EmployeeLocationService employeeLocationService;

    @PostMapping("/check-in")
    public ResponseEntity<String> checkInEmployee(
        @Valid @RequestBody EmployeeLocationRequest request
    ) {
        log.info("checkInEmployee called for employeeId={}", request.getEmployeeId());
        try {
            // Validate and check employee location
            boolean isInOfficeLocation = employeeLocationService.validateEmployeeLocation(
                request.getEmployeeId(), 
                request.getDeviceIpAddress()
            );

            if (isInOfficeLocation) {
                // Log check-in
                LocationLog log = employeeLocationService.logEmployeeCheckIn(
                    request.getEmployeeId(), 
                    request.getDeviceIpAddress()
                );
                
                return ResponseEntity.ok("Welcome to office");
            } else {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("You are not in office location");
            }
        } catch (EmployeeNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body("Employee not found: " + e.getMessage());
        } catch (DeviceLocationException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Device location error: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Unexpected error occurred: " + e.getMessage());
        }
    }

    @GetMapping("/daily-log/{employeeId}")
    public ResponseEntity<LocationLogResponse> getDailyEmployeeLocationLog(
        @PathVariable String employeeId,
        @RequestParam(required = false) LocalDateTime specificDate
    ) {
        log.info("getDailyEmployeeLocationLog called for employeeId={} specificDate={}", employeeId, specificDate);
        try {
            // If no date specified, use current date
            LocalDateTime dateToCheck = specificDate != null 
                ? specificDate 
                : LocalDateTime.now();

            // Retrieve logs for the employee on the specified date
            List<LocationLog> dailyLogs = employeeLocationService
                .getEmployeeDailyLocationLogs(employeeId, dateToCheck);

            // Calculate total time in office
            Duration totalTimeInOffice = calculateTotalTimeInOffice(dailyLogs);

            // Prepare response
            LocationLogResponse response = LocationLogResponse.builder()
                .employeeId(employeeId)
                .date(dateToCheck.toLocalDate())
                .logs(dailyLogs)
                .totalTimeInOffice(totalTimeInOffice)
                .build();

            return ResponseEntity.ok(response);
        } catch (EmployeeNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(LocationLogResponse.builder()
                    .error("Employee not found: " + e.getMessage())
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(LocationLogResponse.builder()
                    .error("Unexpected error: " + e.getMessage())
                    .build());
        }
    }

    private Duration calculateTotalTimeInOffice(List<LocationLog> logs) {
        if (logs == null || logs.size() < 2) {
            return Duration.ZERO;
        }

        // Assuming logs are sorted by timestamp
        Duration totalTime = Duration.ZERO;
        for (int i = 0; i < logs.size(); i += 2) {
            if (i + 1 < logs.size()) {
                LocalDateTime checkIn = logs.get(i).getTimestamp();
                LocalDateTime checkOut = logs.get(i + 1).getTimestamp();
                totalTime = totalTime.plus(Duration.between(checkIn, checkOut));
            }
        }

        return totalTime;
    }
}
