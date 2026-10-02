package com.hrms.employee.management.utility;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import lombok.extern.log4j.Log4j2;

/**
 * Runs background work once per tenant. Scheduler threads never pass through TenantFilter, so
 * without this TenantContext is null and every query falls back to the default tenant's database.
 */
@Component
@Log4j2
public class TenantJobRunner {

    private final TenantRegistry tenantRegistry;

    public TenantJobRunner(TenantRegistry tenantRegistry) {
        this.tenantRegistry = tenantRegistry;
    }

    public void forEachTenant(String jobName, Consumer<String> job) {
        List<String> tenantIds = tenantRegistry.getTenantIds();
        log.info("{} started for {} tenant(s)", jobName, tenantIds.size());
        for (String tenantId : tenantIds) {
            TenantContext.setCurrentTenant(tenantId);
            try {
                job.accept(tenantId);
            } catch (Exception e) {
                // One tenant failing (DB down, company service error) must not stop the others.
                log.error("{} failed for tenant={}: {}", jobName, tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("{} completed", jobName);
    }
}
