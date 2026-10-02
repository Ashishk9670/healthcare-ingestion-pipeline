package com.example.hdip.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {
    private Jwt jwt(Map<String, Object> claims) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }

    @Test
    void mapsRealmRoles() {
        var auths = new KeycloakRealmRoleConverter().convert(jwt(Map.of("sub", "u", "realm_access", Map.of("roles", List.of("INGESTOR", "clinician")))));
        assertThat(auths).extracting(Object::toString).containsExactlyInAnyOrder("ROLE_INGESTOR", "ROLE_CLINICIAN");
    }

    @Test
    void noRolesWhenClaimMissingOrMalformed() {
        assertThat(new KeycloakRealmRoleConverter().convert(jwt(Map.of("sub", "u")))).isEmpty();
        assertThat(new KeycloakRealmRoleConverter().convert(jwt(Map.of("sub", "u", "realm_access", "oops")))).isEmpty();
    }
}
