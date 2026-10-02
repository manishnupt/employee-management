package com.hrms.employee.management.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hrms.employee.management.utility.TenantJobRunner;

/**
 * ddl-auto is none and each tenant has its own database, so the disbursal_run table is created
 * here in every tenant database. Safe to run repeatedly.
 */
@Component
public class DisbursalRunSchemaInitializer {

    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS disbursal_run ("
            + " id BIGSERIAL PRIMARY KEY,"
            + " tenant_id VARCHAR(100) NOT NULL,"
            + " kind VARCHAR(20) NOT NULL,"
            + " type_name VARCHAR(255) NOT NULL,"
            + " frequency VARCHAR(20) NOT NULL,"
            + " period_key VARCHAR(20) NOT NULL,"
            + " days_per_employee DOUBLE PRECISION NOT NULL,"
            + " created_at TIMESTAMP NOT NULL DEFAULT now(),"
            + " CONSTRAINT uk_disbursal_run UNIQUE (tenant_id, kind, type_name, period_key))";

    private final JdbcTemplate jdbcTemplate;
    private final TenantJobRunner tenantJobRunner;

    public DisbursalRunSchemaInitializer(JdbcTemplate jdbcTemplate, TenantJobRunner tenantJobRunner) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantJobRunner = tenantJobRunner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createDisbursalRunTable() {
        tenantJobRunner.forEachTenant("disbursal_run schema init", tenantId -> jdbcTemplate.execute(CREATE_TABLE));
    }
}
