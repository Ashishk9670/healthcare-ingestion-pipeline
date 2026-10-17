package com.example.hdip.consent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Consent decisions (CLINICIAN only, enforced in SecurityConfig). The MRN travels in the body, never the URL, and is
 * never echoed back.
 */
@RestController
@RequestMapping("/api/v1/consent")
public class ConsentController {
    public record ConsentRequest(@NotBlank String mrn, @NotNull ConsentScope scope, @NotNull ConsentStatus status, Instant effectiveAt) {
    }

    private final ConsentService consent;

    public ConsentController(ConsentService consent) {
        this.consent = consent;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> record(@Valid @RequestBody ConsentRequest req) {
        String id = consent.record(req.mrn(), req.scope(), req.status(), req.effectiveAt());
        return Map.of("consentId", id, "scope", req.scope().name(), "status", req.status().name());
    }
}
