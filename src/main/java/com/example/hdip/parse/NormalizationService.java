package com.example.hdip.parse;

import com.example.hdip.model.CanonicalRecord;
import com.example.hdip.model.RawMessage;
import org.springframework.stereotype.Service;

@Service
public class NormalizationService {
    private final Hl7V2Parser hl7;
    private final FhirJsonNormalizer fhir;

    public NormalizationService(Hl7V2Parser hl7, FhirJsonNormalizer fhir) {
        this.hl7 = hl7;
        this.fhir = fhir;
    }

    public CanonicalRecord normalize(RawMessage raw) {
        return switch (raw.format()) {
            case RawMessage.HL7V2 -> hl7.parse(raw.messageId(), raw.payload());
            case RawMessage.FHIR_JSON -> fhir.parse(raw.messageId(), raw.payload());
            default -> throw new MessageParseException("Unsupported format: " + raw.format());
        };
    }
}
