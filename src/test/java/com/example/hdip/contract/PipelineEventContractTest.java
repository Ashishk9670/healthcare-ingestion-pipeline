package com.example.hdip.contract;

import com.example.hdip.crypto.AesGcmFieldCipher;
import com.example.hdip.enrich.CodingLookup;
import com.example.hdip.enrich.EnrichmentService;
import com.example.hdip.extract.ExtractionStage;
import com.example.hdip.extract.ExtractionStore;
import com.example.hdip.extract.RuleBasedExtractor;
import com.example.hdip.kafka.PipelineStages;
import com.example.hdip.kafka.Topics;
import com.example.hdip.model.RawMessage;
import com.example.hdip.observability.PipelineMetrics;
import com.example.hdip.parse.FhirJsonNormalizer;
import com.example.hdip.parse.Hl7V2Parser;
import com.example.hdip.parse.NormalizationService;
import com.example.hdip.store.PatientStoreService;
import com.example.hdip.synthetic.SyntheticDataGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consumer-driven style contract: every event the real stages publish on phi.raw / normalized / enriched / extracted
 * must validate against the JSON Schemas in docs/schemas. Synthetic data only.
 */
@ExtendWith(MockitoExtension.class)
class PipelineEventContractTest {
    static final Path SCHEMA_DIR = Path.of("docs", "schemas");

    /** Mirrors application.yml: ISO-8601 dates (no timestamps) and non_null inclusion. */
    final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL).build();

    @Mock KafkaTemplate<String, String> kafka;
    @Mock PatientStoreService store;
    @Mock ExtractionStore extractionStore;
    @Mock AesGcmFieldCipher cipher;

    JsonSchemaFactory factory;
    PipelineStages stages;
    ExtractionStage extraction;

    @BeforeEach
    void setUp() {
        String base = "https://hdip.example/schemas/";
        String dir = SCHEMA_DIR.toAbsolutePath().toUri().toString();
        factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                b -> b.schemaMappers(m -> m.mapPrefix(base, dir)));
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);
        var norm = new NormalizationService(new Hl7V2Parser(), new FhirJsonNormalizer(mapper));
        var enrich = new EnrichmentService(new CodingLookup(), clock);
        var metrics = new PipelineMetrics(new SimpleMeterRegistry());
        stages = new PipelineStages(mapper, kafka, norm, enrich, store, metrics);
        extraction = new ExtractionStage(mapper, kafka, new RuleBasedExtractor(), extractionStore, cipher, metrics, clock);
    }

    private JsonSchema schema(String topic) {
        return factory.getSchema(new File(SCHEMA_DIR.toFile(), topic + ".schema.json").toURI());
    }

    private Set<ValidationMessage> validate(String topic, String json) {
        return schema(topic).validate(json, InputFormat.JSON);
    }

    private void assertValid(String topic, String json) {
        // Messages only name JSON paths / keywords, never values, so they are safe to print.
        assertThat(validate(topic, json)).as("%s contract violations", topic).isEmpty();
    }

    /** Runs a synthetic message through all stages, returning the JSON published per topic. */
    private List<String[]> runPipeline(String format, String payload, int index) throws Exception {
        when(cipher.hmac(anyString())).thenReturn("hmac-" + index);
        String raw = mapper.writeValueAsString(new RawMessage("m-" + index, format, payload, Instant.now(), "contract-test"));
        assertValid(Topics.RAW, raw);
        stages.onRaw(raw);
        List<String[]> published = new ArrayList<>();
        String normalized = lastSent(Topics.NORMALIZED, published);
        stages.onNormalized(normalized);
        extraction.onNormalized(normalized);
        lastSent(Topics.ENRICHED, published);
        lastSent(Topics.EXTRACTED, published);
        return published;
    }

    private String lastSent(String topic, List<String[]> sink) {
        ArgumentCaptor<String> t = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> v = ArgumentCaptor.forClass(String.class);
        verify(kafka, atLeastOnce()).send(t.capture(), anyString(), v.capture());
        String found = null;
        for (int i = 0; i < t.getAllValues().size(); i++) {
            if (t.getAllValues().get(i).equals(topic)) found = v.getAllValues().get(i);
        }
        if (found != null) sink.add(new String[] {topic, found});
        return found;
    }

    @Test
    void everyPublishedEventMatchesItsSchema() throws Exception {
        var gen = new SyntheticDataGenerator(mapper, new Random(7));
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 12; i++) {
            var p = gen.patient();
            String format = i % 2 == 0 ? RawMessage.HL7V2 : RawMessage.FHIR_JSON;
            String payload = i % 2 == 0 ? gen.hl7Message(p) : gen.fhirBundle(p);
            // fresh mock interactions per message
            org.mockito.Mockito.clearInvocations(kafka);
            for (String[] ev : runPipeline(format, payload, i)) {
                assertValid(ev[0], ev[1]);
                seen.add(ev[0]);
            }
        }
        assertThat(seen).containsExactlyInAnyOrder(Topics.NORMALIZED, Topics.ENRICHED, Topics.EXTRACTED);
    }

    @Test
    void extractedEventsNeverCarryDirectIdentifiers() throws Exception {
        var gen = new SyntheticDataGenerator(mapper, new Random(11));
        var p = gen.patient();
        org.mockito.Mockito.clearInvocations(kafka);
        for (String[] ev : runPipeline(RawMessage.HL7V2, gen.hl7Message(p), 99)) {
            if (ev[0].equals(Topics.EXTRACTED)) {
                JsonNode n = mapper.readTree(ev[1]);
                assertThat(n.has("mrn")).isFalse();
                assertThat(ev[1]).doesNotContain(p.mrn(), p.family(), p.given(), p.ssn());
            }
        }
    }

    @Test
    void rawSchemaRejectsBadEnvelopes() {
        assertThat(validate(Topics.RAW, "{\"messageId\":\"x\",\"format\":\"CSV\",\"payload\":\"p\",\"receivedAt\":\"2026-01-01T00:00:00Z\"}"))
                .isNotEmpty();
        assertThat(validate(Topics.RAW, "{\"format\":\"HL7V2\",\"payload\":\"p\",\"receivedAt\":\"2026-01-01T00:00:00Z\"}")).isNotEmpty();
        assertThat(validate(Topics.RAW, "{\"messageId\":\"x\",\"format\":\"HL7V2\",\"payload\":\"p\",\"receivedAt\":\"2026-01-01T00:00:00Z\",\"extra\":1}"))
                .as("unknown properties signal contract drift").isNotEmpty();
    }

    @Test
    void extractedSchemaRejectsBadFacts() {
        assertThat(validate(Topics.EXTRACTED,
                "{\"messageId\":\"x\",\"mrnHash\":\"h\",\"facts\":[{\"kind\":\"SECRET\",\"value\":\"v\"}],\"extractedAt\":\"2026-01-01T00:00:00Z\"}"))
                .isNotEmpty();
        assertThat(validate(Topics.EXTRACTED,
                "{\"messageId\":\"x\",\"mrnHash\":\"h\",\"facts\":[],\"extractedAt\":\"2026-01-01T00:00:00Z\"}")).isNotEmpty();
    }

    @Test
    void normalizedAndEnrichedSchemasRejectBadShapes() {
        assertThat(validate(Topics.NORMALIZED, "{\"messageId\":\"x\",\"source\":\"HL7V2\"}")).isNotEmpty();
        assertThat(validate(Topics.ENRICHED, "{\"record\":{},\"ageBand\":\"200+\",\"enrichedAt\":\"2026-01-01T00:00:00Z\"}")).isNotEmpty();
    }

    @Test
    void allSchemaFilesPresent() {
        for (String t : List.of(Topics.RAW, Topics.NORMALIZED, Topics.ENRICHED, Topics.EXTRACTED)) {
            assertThat(Files.exists(SCHEMA_DIR.resolve(t + ".schema.json"))).as(t).isTrue();
        }
    }
}
