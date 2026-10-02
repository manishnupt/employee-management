package com.hrms.employee.management.dao;

import com.hrms.employee.management.utility.LeaveTransactionType;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "leave_transactions")
@Data
public class LeaveTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private String employeeId;

    // @Column(name = "leave_type_id", nullable = false)
    // private String leaveTypeId;

    @Column(name = "leave_type_name", nullable = false)
    private String leaveTypeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private LeaveTransactionType transactionType;

    @Column(name = "days", nullable = false)
    private double days;

    /** Leave year the entry belongs to. */
    @Column(name = "year")
    private Integer year;

    @Column(name = "balance_before")
    private Double balanceBefore;

    @Column(name = "balance_after")
    private Double balanceAfter;

    @Column(name = "reason")
    private String reason;

    // @Column(name = "processed_by")
    // private String processedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}