package com.hrms.employee.management.dto;

import lombok.Data;

@Data
public class LeaveTrackerResponse {

    private String status;
    private String message;

    public LeaveTrackerResponse() {
    }
    public LeaveTrackerResponse(String message, String status) {
        this.message = message;
        this.status = status;
    }

}
