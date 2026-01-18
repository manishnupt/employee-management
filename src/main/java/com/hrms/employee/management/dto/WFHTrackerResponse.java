package com.hrms.employee.management.dto;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WFHTrackerResponse {
    private Long id;
    private EmployeeDto employee;
    private String wfhCreditOption;
    private LocalDate startDate;
    private LocalDate endDate;
    private String reason;
    private String status;
    private String createdAt;
    private String updatedAt;

    public WFHTrackerResponse(Long id, LocalDate startDate, LocalDate endDate, String reason, String status) {
        this.id = id;
        this.startDate = startDate;
        this.endDate = endDate;
        this.reason = reason;
        this.status = status;
    }
}
