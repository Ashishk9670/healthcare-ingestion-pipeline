package com.example.hdip.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, @Value("${management.server.port:-1}") int managementPort,
                                   @Value("${server.port:8080}") int serverPort) throws Exception {
        http
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(c -> c.disable()) // stateless bearer-token API
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> {
                        // With a separate management port, actuator endpoints (Prometheus, probes) are not served on the public
                        // port at all and are open on the management port, which must not be exposed publicly.
                        if (managementPort > 0 && managementPort != serverPort) {
                            // match on the connector's local port (robust for the management child context)
                            a.requestMatchers(req -> req.getLocalPort() == managementPort
                                    && req.getRequestURI().startsWith("/actuator/")).permitAll();
                        }
                        a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/", "/index.html", "/app.js", "/config.js", "/style.css", "/favicon.ico").permitAll() // static dashboard shell
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/ingest/**", "/api/v1/synthetic/**").hasRole("INGESTOR")
                        .requestMatchers("/api/v1/export/**").hasRole("ANALYST")
                        .requestMatchers("/graphql").authenticated() // field-level rules via @PreAuthorize
                        .anyRequest().denyAll();
                })
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        c.setPrincipalClaimName("preferred_username");
        return c;
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.security.jwk-set-uri:http://localhost:8180/realms/healthcare/protocol/openid-connect/certs}") String jwkSetUri,
            @Value("${app.security.issuer:http://localhost:8180/realms/healthcare}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build(); // lazy: no startup dependency on Keycloak
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new JwtIssuerValidator(issuer));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("http://localhost:8080", "http://localhost:3000"));
        cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
