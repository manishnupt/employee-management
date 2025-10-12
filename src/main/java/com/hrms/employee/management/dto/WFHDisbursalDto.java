package com.hrms.employee.management.dto;

import lombok.Data;

@Data
public class WFHDisbursalDto {

    private Long wfhId;
    private String employeeId;
    private String wfhType;
    private int totalDays;
}
