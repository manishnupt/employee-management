package com.hrms.employee.management.dto;

import lombok.Data;

@Data
public class WfhBalanceDto {
    private String wfhTypeName;
    private Integer wfhBalance;
    private int carryForwardDays;
    private double remainingDays;
    private int year;
}
