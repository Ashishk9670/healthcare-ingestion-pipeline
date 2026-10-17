package com.example.hdip.api;

import com.example.hdip.audit.AuditService;
import com.example.hdip.fhir.FhirR4Validator;
import com.example.hdip.fhir.FhirValidationException;
import com.example.hdip.kafka.Topics;
import com.example.hdip.model.RawMessage;
import com.example.hdip.observability.PipelineMetrics;
import com.example.hdip.parse.MessageParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class IngestionService {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final PipelineMetrics metrics;
    private final FhirR4Validator fhirValidator;

    public IngestionService(KafkaTemplate<String, String> kafka, ObjectMapper mapper, AuditService audit, PipelineMetrics metrics,
                            FhirR4Validator fhirValidator) {
        this.fhirValidator = fhirValidator;
        this.kafka = kafka;
        this.mapper = mapper;
        this.audit = audit;
        this.metrics = metrics;
    }

    /** Validates the envelope shape, publishes to phi.raw and writes an audit entry. Returns the message id. */
    public String submit(String format, String payload) {
        try {
            validate(format, payload);
        } catch (MessageParseException e) {
            metrics.ingestRejected("invalid");
            throw e;
        }
        String id = UUID.randomUUID().toString();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String by = auth == null ? "unknown" : auth.getName();
        try {
            RawMessage raw = new RawMessage(id, format, payload, Instant.now(), by);
            kafka.send(Topics.RAW, id, mapper.writeValueAsString(raw));
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish message", e);
        }
        metrics.ingestAccepted(format);
        audit.record("INGEST", "Message", id, "format=" + format + " bytes=" + payload.length());
        return id;
    }

    private void validate(String format, String payload) {
        if (payload == null || payload.isBlank()) throw new MessageParseException("Empty payload");
        if (RawMessage.HL7V2.equals(format)) {
            if (!payload.startsWith("MSH")) throw new MessageParseException("HL7 v2 payload must start with MSH");
        } else if (RawMessage.FHIR_JSON.equals(format)) {
            try {
                JsonNode n = mapper.readTree(payload);
                if (!n.hasNonNull("resourceType")) throw new MessageParseException("Missing resourceType");
                var outcome = fhirValidator.validate(payload);
                if (outcome != null) throw new FhirValidationException(fhirValidator.toJson(outcome));
            } catch (MessageParseException e) {
                throw e;
            } catch (Exception e) {
                throw new MessageParseException("Invalid JSON", e);
            }
        } else {
            throw new MessageParseException("Unsupported format");
        }
    }
}
