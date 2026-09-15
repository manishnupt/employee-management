package com.hrms.employee.management.dto;

import com.hrms.employee.management.utility.DisbursalFrequency;
import lombok.Data;

@Data
public class LeaveType {
        private String id;
        private String name;
        private int totalDays;
        private boolean carryForward;
        private DisbursalFrequency disbursalFrequency;
        private String description;
    }