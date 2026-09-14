package com.hrms.employee.management.dao;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import lombok.Data;

@Entity
@Data
public class Employee {
	
	@Id
    @Column(name = "employee_id", updatable = false, nullable = false, unique = true)
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

	@Column(name = "deleted", nullable = false)
	private boolean deleted = false;

	@OneToMany(mappedBy = "employee", cascade = CascadeType.ALL, orphanRemoval = true)
	@JsonBackReference
    private List<LeaveTracker> leaveHistory;

    @OneToMany(mappedBy = "employee", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<Timesheet> timesheetHistory;
	
	@PrePersist
    public void generateUUID() {
        if (this.employeeId == null) {
            this.employeeId = UUID.randomUUID().toString();
        }
    }
}
