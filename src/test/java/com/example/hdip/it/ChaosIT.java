package com.example.hdip.it;

import com.example.hdip.api.IngestionService;
import com.example.hdip.crypto.AesGcmFieldCipher;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Chaos test: Kafka and then Postgres are cut (Toxiproxy) while synthetic HL7 messages are being ingested.
 * Invariants: every accepted message is eventually persisted exactly once per patient, audit entries stay consistent,
 * no plaintext PHI reaches the database columns, audit details or the (masked) log output, and the pipeline recovers.
 * SYNTHETIC DATA ONLY. The app talks to both dependencies through the proxies; assertions use direct connections.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ChaosIT {
    static final int KAFKA_PROXY_PORT = 8666;
    static final int PG_PROXY_PORT = 8667;
    static final SecureRandom RANDOM = new SecureRandom();

    static final Network NET = Network.newNetwork();
    static final ToxiproxyContainer TOXI = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.9.0").asCompatibleSubstituteFor("shopify/toxiproxy"))
            .withNetwork(NET).withNetworkAliases("toxiproxy");
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withNetwork(NET).withNetworkAliases("postgres");
    static GenericContainer<?> kafka;
    static Proxy kafkaProxy;
    static Proxy pgProxy;
    static JdbcTemplate direct;

    static final String KEY = b64(32);
    static final String HMAC = b64(32);

    static {
        try {
            TOXI.start();
            String advertised = TOXI.getHost() + ":" + TOXI.getMappedPort(KAFKA_PROXY_PORT);
            kafka = new GenericContainer<>("apache/kafka:3.8.0")
                    .withNetwork(NET).withNetworkAliases("kafka")
                    .withEnv("KAFKA_NODE_ID", "1")
                    .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                    .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
                    .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://" + advertised)
                    .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
                    .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                    .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
                    .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
                    .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                    .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                    .withExposedPorts(9092)
                    .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofMinutes(3)));
            Startables.deepStart(Stream.of(kafka, POSTGRES)).join();
            ToxiproxyClient client = new ToxiproxyClient(TOXI.getHost(), TOXI.getControlPort());
            kafkaProxy = client.createProxy("kafka", "0.0.0.0:" + KAFKA_PROXY_PORT, "kafka:9092");
            pgProxy = client.createProxy("postgres", "0.0.0.0:" + PG_PROXY_PORT, "postgres:5432");
            var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            direct = new JdbcTemplate(ds);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static String b64(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String host = TOXI.getHost();
        r.add("spring.datasource.url", () -> "jdbc:postgresql://" + host + ":" + TOXI.getMappedPort(PG_PROXY_PORT) + "/" + POSTGRES.getDatabaseName());
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", () -> host + ":" + TOXI.getMappedPort(KAFKA_PROXY_PORT));
        r.add("app.crypto.encryption-keys", () -> "chaos1:" + KEY);
        r.add("app.crypto.active-key-id", () -> "chaos1");
        r.add("app.crypto.hmac-key", () -> HMAC);
        r.add("management.server.port", () -> AbstractIntegrationTest.freePort());
    }

    @AfterAll
    static void stop() {
        for (var c : new org.testcontainers.lifecycle.Startable[] {kafka, POSTGRES, TOXI}) {
            try {
                if (c != null) c.stop();
            } catch (Exception ignored) {
                // best effort
            }
        }
        NET.close();
    }

    @Autowired IngestionService ingestion;
    @Autowired AesGcmFieldCipher cipher;

    /** Synthetic identifiers for one message; used to prove none of them leak. */
    record Synth(String mrn, String ssn, String family, String given) {
    }

    private static String hl7(Synth s, int i) {
        return String.join("\r",
                "MSH|^~\\&|SYNTH|FAC|HDIP|FAC|20260101120000||ORU^R01|chaos-" + i + "|P|2.5",
                "PID|1||" + s.mrn() + "^^^SYNTH^MR||" + s.family() + "^" + s.given() + "||19700315|F|||||||||||" + s.ssn(),
                "DG1|1||E11.9^Type 2 diabetes^I10",
                "NTE|1||Assessment: type 2 diabetes mellitus. Current therapy: metformin 500 mg twice daily.",
                "OBX|1|NM|4548-4^Hemoglobin A1c^LN||7.2|%|||||F|||20260101103000");
    }

    private static Synth synth(String run, int i) {
        return new Synth("SYN-CHAOS-" + run + "-" + i, String.format("900-%02d-%04d", i % 100, 1000 + i),
                "Qzfam" + run + "x" + i, "Qzgiv" + run + "x" + i);
    }

    @Test
    void kafkaThenPostgresOutageLosesNoDataLeaksNothingAndRecovers(CapturedOutput output) throws Exception {
        String run = Long.toString(System.nanoTime() % 1_000_000, 36);
        Map<String, Synth> accepted = new ConcurrentHashMap<>(); // messageId -> identifiers
        List<Synth> attempted = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean stop = new AtomicBoolean();

        Thread producer = new Thread(() -> {
            int i = 0;
            while (!stop.get()) {
                Synth s = synth(run, i);
                attempted.add(s);
                try {
                    accepted.put(ingestion.submit("HL7V2", hl7(s, i)), s);
                } catch (Exception e) {
                    // Rejected during an outage: the caller saw an error and may retry; not counted as accepted.
                }
                i++;
                sleep(150);
            }
        }, "chaos-producer");
        producer.start();

        sleep(4000);
        kafkaProxy.disable(); // cut Kafka mid-run
        sleep(6000);
        kafkaProxy.enable();
        sleep(6000);
        pgProxy.disable();    // then cut Postgres mid-run
        sleep(6000);
        pgProxy.enable();
        sleep(5000);
        stop.set(true);
        producer.join(60_000);
        assertThat(accepted).as("messages accepted during the run").hasSizeGreaterThan(10);

        Map<String, Synth> byHash = new ConcurrentHashMap<>();
        for (Synth s : attempted) byHash.put(cipher.hmac(s.mrn()), s);
        Set<String> acceptedHashes = new HashSet<>();
        accepted.values().forEach(s -> acceptedHashes.add(cipher.hmac(s.mrn())));

        // 1. no data lost: every accepted message ends up persisted
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(persistedHashes()).containsAll(acceptedHashes));

        // 2. exactly one patient row per MRN hash (unique constraint) and nothing persisted that was never attempted
        Set<String> persisted = persistedHashes();
        assertThat(direct.queryForObject("select count(*) from patient", Integer.class))
                .isEqualTo(persisted.size());
        assertThat(byHash.keySet()).containsAll(persisted);

        // 3. audit consistency
        Set<String> ingestAudited = new HashSet<>(direct.queryForList(
                "select resource_id from audit_entry where action = 'INGEST'", String.class));
        assertThat(ingestAudited).as("every accepted message has an INGEST audit entry").containsAll(accepted.keySet());
        Set<String> persistAudited = new HashSet<>(direct.queryForList(
                "select resource_id from audit_entry where action = 'PIPELINE_PERSIST'", String.class));
        assertThat(persistAudited).as("every persisted patient has a PIPELINE_PERSIST audit entry").containsAll(persisted);
        assertThat(persisted).as("no PIPELINE_PERSIST audit entry without data").containsAll(
                persistAudited.stream().filter(byHash::containsKey).toList());

        // 4. recovery: a fresh message flows end to end after both outages
        Synth probe = synth(run, 9_999_999);
        ingestion.submit("HL7V2", hl7(probe, 9_999_999));
        String probeHash = cipher.hmac(probe.mrn());
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(persistedHashes()).contains(probeHash));
        byHash.put(probeHash, probe);
        attempted.add(probe);

        // 5. nothing leaked: DB columns, audit details, extraction rows
        List<String> encrypted = direct.queryForList("select family_name_enc || given_name_enc || mrn_enc || ssn_enc from patient", String.class);
        List<String> auditText = direct.queryForList("select coalesce(detail,'') || coalesce(resource_id,'') from audit_entry", String.class);
        List<String> extracted = direct.queryForList("select value || coalesce(code,'') from clinical_extraction", String.class);
        for (Synth s : attempted) {
            for (String id : List.of(s.mrn(), s.ssn(), s.family(), s.given())) {
                assertThat(encrypted).as("patient columns").noneMatch(v -> v != null && v.contains(id));
                assertThat(auditText).as("audit entries").noneMatch(v -> v.contains(id));
                assertThat(extracted).as("extraction rows").noneMatch(v -> v.contains(id));
            }
        }
        // masked log output (pattern layout with PHI converters) contains none of the identifiers
        String logs = output.getAll();
        for (Synth s : attempted) {
            for (String id : List.of(s.mrn(), s.ssn(), s.family(), s.given())) {
                assertThat(logs.contains(id)).as("log output must not contain a synthetic identifier").isFalse();
            }
        }
    }

    private static Set<String> persistedHashes() {
        return new HashSet<>(direct.queryForList("select mrn_hash from patient", String.class));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
