package com.hrms.employee.management.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class MultiTenantJwtDecoderConfig {

    private final Map<String, JwtDecoder> decoderCache = new ConcurrentHashMap<>();

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            String issuer = extractIssuerFromToken(token);
            return decoderCache
                    .computeIfAbsent(issuer, this::buildDecoderForIssuer)
                    .decode(token);
        };
    }

    private JwtDecoder buildDecoderForIssuer(String issuer) {
        String jwkSetUri = issuer + "/protocol/openid-connect/certs";
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    private String extractIssuerFromToken(String token) {
        try {
            String[] parts = token.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(payload);
            return json.getString("iss");
        } catch (Exception e) {
            throw new JwtException("Failed to extract issuer from token", e);
        }
    }


}
