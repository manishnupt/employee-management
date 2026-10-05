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
    /** Null when the company service does not send it; unused days are then left to accumulate. */
    private Boolean carryForward;
    /** Optional cap on days carried into the next period. Null means no cap. */
    private Double maxCarryForwardDays;
}
