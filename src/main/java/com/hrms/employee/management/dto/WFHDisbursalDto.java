package com.hrms.employee.management.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import lombok.Data;

@Data
public class WFHDisbursalDto {

    private Long wfhId;
    private String employeeId;
    // The schedule endpoint may send the type name as "name", like /wfh-types does.
    @JsonAlias("name")
    private String wfhType;
    private int totalDays;
}
