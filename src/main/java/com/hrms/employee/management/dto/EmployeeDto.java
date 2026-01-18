package com.hrms.employee.management.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class EmployeeDto {
    private UUID id;
    private String name;
    private String username;
    private String role;
    private String email;
    private String phone;
    private String address;
    private String city;
    private String state;
    private String zipCode;
    private String country;
    private String jobTitle;
    private String project;
    private String jobType;
    private String jobStatus;
    private String jobDescription;
    private String password;
    private boolean temporaryPassword;
    private String kcReferenceId;
}
