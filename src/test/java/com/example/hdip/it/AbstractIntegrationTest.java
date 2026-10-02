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
    static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
            .withRealmImportFile("healthcare-realm.json");

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
        r.add("app.security.issuer", () -> issuer);
        r.add("app.security.jwk-set-uri", () -> issuer + "/protocol/openid-connect/certs");
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

    /** Password-grant token for a synthetic dev user from the realm export (username = "<role>1"). */
    static String token(String username) {
        try {
            String form = "grant_type=password&client_id=pipeline-client&username=" + enc(username)
                    + "&password=" + enc(username + "-dev");
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
