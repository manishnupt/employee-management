package com.hrms.employee.management.dao;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One row per (tenant, kind, type, period) that has been disbursed. The unique key makes a
 * disbursal run idempotent and stops two replicas from crediting the same period twice.
 * Created per tenant database by DisbursalRunSchemaInitializer.
 */
@Entity
@Table(name = "disbursal_run", uniqueConstraints = @UniqueConstraint(
        name = "uk_disbursal_run", columnNames = {"tenant_id", "kind", "type_name", "period_key"}))
@Data
public class DisbursalRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** LEAVE or WFH */
    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "type_name", nullable = false)
    private String typeName;

    @Column(name = "frequency", nullable = false)
    private String frequency;

    @Column(name = "period_key", nullable = false)
    private String periodKey;

    @Column(name = "days_per_employee", nullable = false)
    private double daysPerEmployee;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
