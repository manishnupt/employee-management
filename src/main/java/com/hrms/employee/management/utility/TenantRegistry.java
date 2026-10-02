package com.hrms.employee.management.utility;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.hrms.employee.management.dto.TenantDbConfig;
import com.hrms.employee.management.dto.TenantDbConfigResponse;

import lombok.extern.log4j.Log4j2;

/**
 * Lists tenants from the tenant microservice (api/v1/tenants/databases). Called directly rather than
 * reading MultiTenantConfiguration, which is moving to a shared library.
 */
@Component
@Log4j2
public class TenantRegistry {

    private final RestTemplate restTemplate;
    private final String tenantConfigApiUrl;

    public TenantRegistry(RestTemplate restTemplate, @Value("${tenant.config.api.url}") String tenantConfigApiUrl) {
        this.restTemplate = restTemplate;
        this.tenantConfigApiUrl = tenantConfigApiUrl;
    }

    /** Fetched on every call so tenants onboarded after startup are picked up. */
    public List<String> getTenantIds() {
        ResponseEntity<TenantDbConfigResponse> response = restTemplate.getForEntity(tenantConfigApiUrl, TenantDbConfigResponse.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null || response.getBody().getData() == null) {
            throw new IllegalStateException("Failed to fetch tenants from " + tenantConfigApiUrl + ", status=" + response.getStatusCode());
        }
        List<String> tenantIds = response.getBody().getData().stream()
                .map(TenantDbConfig::getTenantId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        log.debug("Fetched {} tenant(s) from {}: {}", tenantIds.size(), tenantConfigApiUrl, tenantIds);
        return tenantIds;
    }
}
