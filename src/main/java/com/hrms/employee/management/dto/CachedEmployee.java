package com.hrms.employee.management.dto;

import java.time.LocalDateTime;

import com.hrms.employee.management.dao.Employee;

import lombok.Data;

/**
 * Flat copy of {@link Employee} stored in Redis. Kept separate from the entity so the
 * entity's JSON annotations (JsonIgnore on timestamps, lazy collections) don't affect caching.
 */
@Data
public class CachedEmployee {
    private String employeeId;
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
    private Long groupId;
    private String kcReferenceId;
    private String assignedManagerId;
    private boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CachedEmployee from(Employee e) {
        CachedEmployee c = new CachedEmployee();
        c.employeeId = e.getEmployeeId();
        c.name = e.getName();
        c.username = e.getUsername();
        c.role = e.getRole();
        c.email = e.getEmail();
        c.phone = e.getPhone();
        c.address = e.getAddress();
        c.city = e.getCity();
        c.state = e.getState();
        c.zipCode = e.getZipCode();
        c.country = e.getCountry();
        c.jobTitle = e.getJobTitle();
        c.project = e.getProject();
        c.jobType = e.getJobType();
        c.jobStatus = e.getJobStatus();
        c.jobDescription = e.getJobDescription();
        c.groupId = e.getGroupId();
        c.kcReferenceId = e.getKcReferenceId();
        c.assignedManagerId = e.getAssignedManagerId();
        c.deleted = e.isDeleted();
        c.createdAt = e.getCreatedAt();
        c.updatedAt = e.getUpdatedAt();
        return c;
    }

    /** Builds a detached Employee. Only use it for reads, never save it back to the DB. */
    public Employee toEmployee() {
        Employee e = new Employee();
        e.setEmployeeId(employeeId);
        e.setName(name);
        e.setUsername(username);
        e.setRole(role);
        e.setEmail(email);
        e.setPhone(phone);
        e.setAddress(address);
        e.setCity(city);
        e.setState(state);
        e.setZipCode(zipCode);
        e.setCountry(country);
        e.setJobTitle(jobTitle);
        e.setProject(project);
        e.setJobType(jobType);
        e.setJobStatus(jobStatus);
        e.setJobDescription(jobDescription);
        e.setGroupId(groupId);
        e.setKcReferenceId(kcReferenceId);
        e.setAssignedManagerId(assignedManagerId);
        e.setDeleted(deleted);
        e.setCreatedAt(createdAt);
        e.setUpdatedAt(updatedAt);
        return e;
    }
}
