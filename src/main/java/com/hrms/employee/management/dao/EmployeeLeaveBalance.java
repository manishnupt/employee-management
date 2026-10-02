package com.hrms.employee.management.dao;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "employee_leave_balance")
@Data
public class EmployeeLeaveBalance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private String employeeId;

    // @Column(name = "leave_type_id", nullable = false)
    // private String leaveTypeId;

    @Column(name = "leave_type_name", nullable = false)
    private String leaveTypeName;

    @Column(name = "leave_balance", nullable = false)
    private double leaveBalance;

    // @Column(name = "used_days", nullable = false)
    // private int usedDays;

    @Column(name = "carry_forward_days", nullable = false)
    private double carryForwardDays;

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

    // @ManyToOne(fetch = FetchType.LAZY)
    // @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    // private Employee employee;

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
        this.remainingDays = this.leaveBalance + this.carryForwardDays ;
    }

    public void addDays(int days) {
        this.leaveBalance += days;
        calculateRemainingDays();
    }

    /** Total available: this year's accrual plus days carried forward. */
    public double getAvailableDays() {
        return this.leaveBalance + this.carryForwardDays;
    }

    /** Deducts days, using carried-forward days first, then this year's accrual. */
    public void deductDays(double days) {
        double fromCarryForward = Math.min(Math.max(this.carryForwardDays, 0), days);
        this.carryForwardDays -= fromCarryForward;
        this.leaveBalance -= days - fromCarryForward;
        calculateRemainingDays();
    }

}
