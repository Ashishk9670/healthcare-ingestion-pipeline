package com.example.hdip.parse;

import com.example.hdip.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Normalizes a FHIR-like JSON Bundle (Patient, Observation, Condition) to the canonical model. */
@Component
public class FhirJsonNormalizer {

    private final ObjectMapper mapper;

    public FhirJsonNormalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CanonicalRecord parse(String messageId, String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new MessageParseException("Invalid JSON payload", e);
        }
        if (root == null || !root.isObject() || !root.hasNonNull("resourceType")) {
            throw new MessageParseException("Payload is not a FHIR resource (missing resourceType)");
        }
        List<JsonNode> resources = new ArrayList<>();
        if ("Bundle".equals(root.path("resourceType").asText())) {
            for (JsonNode entry : root.path("entry")) {
                if (entry.has("resource")) resources.add(entry.get("resource"));
            }
        } else {
            resources.add(root);
        }

        CanonicalPatient patient = null;
        List<CanonicalObservation> observations = new ArrayList<>();
        List<CanonicalDiagnosis> diagnoses = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (JsonNode r : resources) {
            switch (r.path("resourceType").asText()) {
                case "Patient" -> patient = patient(r);
                case "Observation" -> observations.add(observation(r));
                case "Condition" -> diagnoses.add(condition(r));
                case "DocumentReference" -> notes.addAll(notes(r));
                default -> { /* ignored */ }
            }
        }
        if (patient == null) throw new MessageParseException("FHIR payload has no Patient resource");
        if (patient.mrn() == null) throw new MessageParseException("Patient.identifier is required");
        String id = messageId != null ? messageId : root.path("id").asText(null);
        return new CanonicalRecord(id, RawMessage.FHIR_JSON, patient, observations, diagnoses, notes);
    }

    private CanonicalPatient patient(JsonNode p) {
        String mrn = null;
        String ssn = null;
        for (JsonNode idn : p.path("identifier")) {
            String system = idn.path("system").asText("").toLowerCase();
            String value = idn.path("value").asText(null);
            if (system.contains("ssn")) ssn = value;
            else if (mrn == null) mrn = value;
        }
        JsonNode name = p.path("name").path(0);
        String family = name.path("family").asText(null);
        String given = name.path("given").path(0).asText(null);
        LocalDate dob = p.hasNonNull("birthDate") ? parseDate(p.get("birthDate").asText()) : null;
        return new CanonicalPatient(mrn, family, given, dob, p.path("gender").asText(null), ssn);
    }

    private CanonicalObservation observation(JsonNode o) {
        JsonNode coding = o.path("code").path("coding").path(0);
        BigDecimal num = null;
        String text = null;
        String unit = null;
        if (o.has("valueQuantity")) {
            JsonNode q = o.get("valueQuantity");
            if (q.hasNonNull("value")) num = q.get("value").decimalValue();
            unit = q.path("unit").asText(null);
        } else if (o.hasNonNull("valueString")) {
            text = o.get("valueString").asText();
        }
        Instant at = null;
        if (o.hasNonNull("effectiveDateTime")) {
            try {
                at = OffsetDateTime.parse(o.get("effectiveDateTime").asText()).toInstant();
            } catch (Exception e) {
                throw new MessageParseException("Invalid effectiveDateTime", e);
            }
        }
        return new CanonicalObservation(coding.path("code").asText(null), coding.path("system").asText(null),
                coding.path("display").asText(o.path("code").path("text").asText(null)), text, num, unit, at);
    }

    private CanonicalDiagnosis condition(JsonNode c) {
        JsonNode coding = c.path("code").path("coding").path(0);
        return new CanonicalDiagnosis(coding.path("code").asText(null), coding.path("system").asText(null),
                coding.path("display").asText(c.path("code").path("text").asText(null)));
    }

    /** Free-text notes: DocumentReference.content[].attachment with text/* contentType and base64 data. */
    private List<String> notes(JsonNode d) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : d.path("content")) {
            JsonNode a = c.path("attachment");
            if (a.path("contentType").asText("").startsWith("text/") && a.hasNonNull("data")) {
                try {
                    out.add(new String(java.util.Base64.getDecoder().decode(a.get("data").asText()), java.nio.charset.StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    throw new MessageParseException("Invalid base64 note attachment", e);
                }
            }
        }
        return out;
    }

    private LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            throw new MessageParseException("Invalid birthDate", e);
        }
    }
}
