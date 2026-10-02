package com.hrms.employee.management.utility;

import java.util.List;

/** Tenant ids that have a datasource configured in {@link MultitenantDataSource}. */
public class TenantRegistry {

    private final List<String> tenantIds;

    public TenantRegistry(List<String> tenantIds) {
        this.tenantIds = List.copyOf(tenantIds);
    }

    public List<String> getTenantIds() {
        return tenantIds;
    }
}
