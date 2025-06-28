package com.hrms.employee.management.dto;

import com.hrms.employee.management.utility.DisbursalFrequency;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Data;

@Data
public class LeaveDisbursalDto {

    private String id;

    private String name;
    private int totalDays;
    private boolean carryForward;
    @Enumerated(EnumType.STRING)
    private DisbursalFrequency disbursalFrequency;
    private String description;
}