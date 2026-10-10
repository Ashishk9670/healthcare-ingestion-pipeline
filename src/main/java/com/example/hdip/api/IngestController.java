package com.example.hdip.api;

import com.example.hdip.model.RawMessage;
import com.example.hdip.ratelimit.IngestProperties;
import com.example.hdip.synthetic.SyntheticDataGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class IngestController {
    private final IngestionService ingestion;
    private final SyntheticDataGenerator generator;
    private final IngestProperties props;

    public IngestController(IngestionService ingestion, SyntheticDataGenerator generator, IngestProperties props) {
        this.props = props;
        this.ingestion = ingestion;
        this.generator = generator;
    }

    @PostMapping(value = "/ingest/hl7", consumes = {MediaType.TEXT_PLAIN_VALUE, "x-application/hl7-v2+er7"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> ingestHl7(@RequestBody String body) {
        return Map.of("messageId", ingestion.submit(RawMessage.HL7V2, body));
    }

    @PostMapping(value = "/ingest/fhir", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/fhir+json"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> ingestFhir(@RequestBody String body) {
        return Map.of("messageId", ingestion.submit(RawMessage.FHIR_JSON, body));
    }

    /** Generates and ingests SYNTHETIC patients (alternating HL7 / FHIR). */
    @PostMapping("/synthetic")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> synthetic(@RequestParam(defaultValue = "10") int count) {
        int n = Math.max(1, Math.min(count, props.maxSyntheticCount()));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(i % 2 == 0
                    ? ingestion.submit(RawMessage.HL7V2, generator.hl7Message())
                    : ingestion.submit(RawMessage.FHIR_JSON, generator.fhirBundle()));
        }
        return Map.of("submitted", n, "synthetic", true, "messageIds", ids);
    }
}
