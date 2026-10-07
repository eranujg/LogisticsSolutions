package com.aadvixon.tms.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless API security: bearer JWTs from Keycloak (locally) or Cognito (AWS).
 *
 * <p>Authentication happens here; authorisation (module permissions) is checked
 * per request by {@link PermissionInterceptor} using the user's roles.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    @Value("${tms.tenancy.header-enabled:false}") boolean headerEnabled,
                                    @Value("${tms.platform.admin-api-enabled:false}") boolean platformApiEnabled)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/v1/system/**", "/actuator/health", "/actuator/info").permitAll();
                    auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll();
                    if (platformApiEnabled) {
                        auth.requestMatchers("/api/v1/platform/**").permitAll();
                    }
                    if (headerEnabled) {
                        // Development: requests without a token fall back to the X-Tenant-Id header.
                        auth.anyRequest().permitAll();
                    } else {
                        auth.anyRequest().authenticated();
                    }
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Validates tokens against the identity provider's issuer (signature, expiry, issuer).
     * Discovery is lazy, so the backend starts even when Keycloak is not running.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${tms.security.jwt.issuer-uri:}") String issuerUri) {
        if (issuerUri == null || issuerUri.isBlank()) {
            return token -> {
                throw new BadJwtException("Login is not configured (tms.security.jwt.issuer-uri is empty)");
            };
        }
        return new SupplierJwtDecoder(() -> JwtDecoders.fromIssuerLocation(issuerUri));
    }
}
