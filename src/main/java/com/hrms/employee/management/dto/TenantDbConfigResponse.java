package com.hrms.employee.management.dto;

import lombok.Data;

import java.util.List;

@Data
public class TenantDbConfigResponse {
    private int code;
    private String message;
    private List<TenantDbConfig> data;
}

