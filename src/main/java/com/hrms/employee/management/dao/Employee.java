package com.hrms.employee.management.dao;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

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

	@Column(name = "created_at", nullable = false, updatable = false)
	@JsonIgnore
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	@JsonIgnore
	private LocalDateTime updatedAt;

	@OneToMany(mappedBy = "employee", cascade = CascadeType.ALL, orphanRemoval = true , fetch = FetchType.LAZY)
	@JsonBackReference
    private List<LeaveTracker> leaveHistory;

    @OneToMany(mappedBy = "employee", cascade = CascadeType.ALL, orphanRemoval = true , fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Timesheet> timesheetHistory;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
		updatedAt = LocalDateTime.now();
		if (this.employeeId == null) {
			this.employeeId = UUID.randomUUID().toString();
		}
	}

	@PreUpdate
	protected void onUpdate() {
		updatedAt = LocalDateTime.now();
	}
}
