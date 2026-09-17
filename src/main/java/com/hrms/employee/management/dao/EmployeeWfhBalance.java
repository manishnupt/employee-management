package com.hrms.employee.management.dao;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
public class EmployeeWfhBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String wfhTypeName;// quarterly,monthly,yearly,half_yearly
    private String employeeId;
    private Integer wfhBalance;

    @Column(name = "carry_forward_days", nullable = false)
    private int carryForwardDays;

    @Column(name = "remaining_days", nullable = false)
    private double remainingDays;

    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        calculateRemainingDays();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        calculateRemainingDays();
    }

    private void calculateRemainingDays() {
        this.remainingDays = this.wfhBalance + this.carryForwardDays ;
    }

    public void addDays(int days) {
        this.wfhBalance += days;
        calculateRemainingDays();
    }

}
