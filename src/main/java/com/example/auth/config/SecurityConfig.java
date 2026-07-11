package com.example.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration.
 *
 * Public endpoints (no token required):
 *   POST /api/auth/login      – password-based login → returns Cognito JWTs
 *
 * Protected endpoints (Bearer token required):
 *   GET  /api/auth/validate   – verifies a token is active via Cognito GetUser
 *   GET  /api/auth/profile    – returns claims from the JWT itself (no AWS call)
 *   GET  /api/users/{username} – admin user lookup
 *
 * Spring automatically validates the JWT signature and expiry using the
 * Cognito JWKS URI configured in application.yml.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public
                .requestMatchers(
                    "/api/auth/login",
                    "/actuator/health",
                    "/actuator/info",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**"
                ).permitAll()
                // Everything else requires a valid Cognito JWT
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );

        return http.build();
    }

    /**
     * Maps Cognito JWT claims to Spring Security authorities.
     * Cognito groups are in the "cognito:groups" claim — extend this to
     * map them to ROLE_* if you use Cognito User Pool Groups.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            // Default: grant ROLE_USER for all authenticated Cognito users.
            // To use Cognito groups:
            //   List<String> groups = jwt.getClaimAsStringList("cognito:groups");
            //   return groups.stream().map(g -> new SimpleGrantedAuthority("ROLE_" + g))
            //                .collect(Collectors.toList());
            return AuthorityUtils.createAuthorityList("ROLE_USER");
        });
        return converter;
    }
}
