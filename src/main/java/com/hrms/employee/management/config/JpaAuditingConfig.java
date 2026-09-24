package com.hrms.employee.management.config;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.hrms.employee.management.utility.UserContext;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    // Used when there is no authenticated caller, e.g. scheduled jobs
    private static final String SYSTEM_USER = "SYSTEM";

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.ofNullable(UserContext.getCurrentUser())
                .filter(user -> !user.isBlank())
                .or(() -> Optional.of(SYSTEM_USER));
    }
}
