package com.example.hdip.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;

/**
 * Starts real Postgres, Kafka and Keycloak (with the docker-compose realm export) via Testcontainers.
 * Containers are JVM-singletons shared by all *IT classes. Requires Docker. SYNTHETIC DATA ONLY.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    static final SecureRandom RANDOM = new SecureRandom();
    /** Per-run random passwords for the realm's synthetic users; injected via env placeholders in the realm export. */
    static final Map<String, String> PASSWORDS = new HashMap<>();
    static final int MANAGEMENT_PORT = freePort();

    static {
        for (String u : List.of("ingestor1", "clinician1", "analyst1", "auditor1")) PASSWORDS.put(u, randomSecret(18));
    }

    static final KeycloakContainer KEYCLOAK = withPasswords(new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
            .withRealmImportFile("healthcare-realm.json"));

    private static KeycloakContainer withPasswords(KeycloakContainer c) {
        PASSWORDS.forEach((u, pw) -> c.withEnv("HDIP_PW_" + u.toUpperCase(), pw));
        return c;
    }

    static String randomSecret(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static {
        Startables.deepStart(Stream.of(POSTGRES, KAFKA, KEYCLOAK)).join();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        String issuer = KEYCLOAK.getAuthServerUrl() + "/realms/healthcare";
        r.add("app.crypto.encryption-keys", () -> "it1:" + randomKey());
        r.add("app.crypto.active-key-id", () -> "it1");
        r.add("app.crypto.hmac-key", () -> IT_HMAC);
        r.add("management.server.port", () -> MANAGEMENT_PORT);
        r.add("app.security.issuer", () -> issuer);
        r.add("app.security.jwk-set-uri", () -> issuer + "/protocol/openid-connect/certs");
    }

    private static final String IT_KEY = randomKeyValue();
    private static final String IT_HMAC = randomKeyValue();

    static String randomKey() {
        return IT_KEY;
    }

    private static String randomKeyValue() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    record Resp(int status, String body) {
        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Password-grant token for a synthetic dev user from the realm export (username = "<role>1"); passwords are random per test run. */
    static String token(String username) {
        try {
            String form = "grant_type=password&client_id=pipeline-client&username=" + enc(username)
                    + "&password=" + enc(PASSWORDS.get(username));
            HttpRequest req = HttpRequest.newBuilder(URI.create(KEYCLOAK.getAuthServerUrl() + "/realms/healthcare/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("token request failed: " + res.statusCode());
            return JSON.readTree(res.body()).get("access_token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    Resp http(String method, String path, String token, String contentType, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (token != null) b.header("Authorization", "Bearer " + token);
            if (contentType != null) b.header("Content-Type", contentType);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(res.statusCode(), res.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Resp graphql(String token, String query) {
        try {
            return http("POST", "/graphql", token, "application/json", JSON.writeValueAsString(Map.of("query", query)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
