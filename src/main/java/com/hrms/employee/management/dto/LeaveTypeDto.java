package com.hrms.employee.management.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LeaveTypeDto {
    private String id;
    private String name;
    private int totalDays;
    private boolean carryForward;
    private String disbursalFrequency; 
    private String description;
}

