package com.hrms.employee.management.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.hrms.employee.management.dao.DisbursalRun;

@Repository
public interface DisbursalRunRepository extends JpaRepository<DisbursalRun, Long> {

    boolean existsByTenantIdAndKindAndTypeNameAndPeriodKey(String tenantId, String kind, String typeName, String periodKey);

    /**
     * Claims a disbursal period. Returns 1 if this caller now owns it, 0 if it was already disbursed.
     * Must run in the same transaction as the crediting: a concurrent claimer blocks on the
     * uncommitted row, then gets 0 if this transaction commits, or 1 if it rolls back.
     */
    @Modifying
    @Query(value = "INSERT INTO disbursal_run (tenant_id, kind, type_name, frequency, period_key, days_per_employee, created_at) "
            + "VALUES (:tenantId, :kind, :typeName, :frequency, :periodKey, :daysPerEmployee, now()) "
            + "ON CONFLICT (tenant_id, kind, type_name, period_key) DO NOTHING", nativeQuery = true)
    int claim(@Param("tenantId") String tenantId,
              @Param("kind") String kind,
              @Param("typeName") String typeName,
              @Param("frequency") String frequency,
              @Param("periodKey") String periodKey,
              @Param("daysPerEmployee") double daysPerEmployee);
}
