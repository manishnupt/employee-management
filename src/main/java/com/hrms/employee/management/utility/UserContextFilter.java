package com.hrms.employee.management.utility;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;

/**
 * Resolves the calling user from the bearer token so audit columns (createdBy / modifiedBy)
 * can be populated. The token signature is NOT verified here; it is assumed to be validated
 * upstream (gateway / Keycloak). The value is used only for audit attribution.
 */
@Component
@Order(2)
@Log4j2
class UserContextFilter implements Filter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        UserContext.setCurrentUser(extractUser(req.getHeader("Authorization")));

        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private String extractUser(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        try {
            String[] parts = authorization.substring(7).trim().split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = OBJECT_MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
            // "sub" is the Keycloak user id, which is also the employeeId in this service
            if (claims.hasNonNull("sub")) {
                return claims.get("sub").asText();
            }
            if (claims.hasNonNull("preferred_username")) {
                return claims.get("preferred_username").asText();
            }
        } catch (Exception e) {
            log.warn("Unable to resolve user from Authorization header: {}", e.getMessage());
        }
        return null;
    }
}
