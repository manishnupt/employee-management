package com.hrms.employee.management.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(t -> t.disable());
        http.authorizeHttpRequests(auth -> {
            auth.anyRequest().authenticated();
        });
        http.oauth2ResourceServer(
            oauth2 ->{
               oauth2.jwt(Customizer.withDefaults());
            });
        http.sessionManagement(t -> t.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

}
