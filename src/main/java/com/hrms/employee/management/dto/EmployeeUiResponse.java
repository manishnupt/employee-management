package com.hrms.employee.management.dto;


import lombok.Data;

@Data
public class EmployeeUiResponse {
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

    private ManagerInfoResponse assignedManager;
    private RoleGroupExtResponse assignedGroup;

    @Data
    public static class ManagerInfoResponse {
        private String managerId;
        private String name;
    }
    @Data
    public static class RoleGroupExtResponse {
        private Long id;
        private String name;
    }

}
