package com.hrms.employee.management.dto;

import lombok.Data;

@Data
public class LeaveBalanceDto {
    private String leaveTypeName;
    private double leaveBalance;
    private double carryForwardDays;
    private double remainingDays;
    private int year;
}