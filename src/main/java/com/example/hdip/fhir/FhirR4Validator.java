package com.example.hdip.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Validates FHIR R4 JSON with HAPI FHIR's profile validator. Failures are returned as an {@link OperationOutcome}
 * that carries severity, issue type and element path only; message text has quoted values redacted so that
 * payload content (potential PHI) is never echoed back or logged.
 */
@Component
public class FhirR4Validator {
    private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"");

    private final FhirContext ctx = FhirContext.forR4Cached();
    private volatile FhirValidator validator;

    private FhirValidator validator() {
        FhirValidator v = validator;
        if (v == null) {
            synchronized (this) {
                v = validator;
                if (v == null) {
                    var chain = new ValidationSupportChain(new DefaultProfileValidationSupport(ctx),
                            new InMemoryTerminologyServerValidationSupport(ctx), new CommonCodeSystemsTerminologyService(ctx));
                    var instance = new FhirInstanceValidator(chain);
                    instance.setErrorForUnknownProfiles(false);
                    instance.setAnyExtensionsAllowed(true);
                    v = ctx.newValidator().registerValidatorModule(instance);
                    validator = v;
                }
            }
        }
        return v;
    }

    /** Returns null when the payload is valid; otherwise a sanitized OperationOutcome containing the errors. */
    public OperationOutcome validate(String json) {
        ValidationResult result = validator().validateWithResult(json);
        OperationOutcome oo = new OperationOutcome();
        for (SingleValidationMessage m : result.getMessages()) {
            ResultSeverityEnum sev = m.getSeverity();
            if (sev != ResultSeverityEnum.ERROR && sev != ResultSeverityEnum.FATAL) continue;
            oo.addIssue()
                    .setSeverity(sev == ResultSeverityEnum.FATAL ? OperationOutcome.IssueSeverity.FATAL : OperationOutcome.IssueSeverity.ERROR)
                    .setCode(OperationOutcome.IssueType.STRUCTURE)
                    .setDiagnostics(redact(m.getMessage()))
                    .addExpression(m.getLocationString() == null ? "Resource" : redact(m.getLocationString()));
        }
        return oo.hasIssue() ? oo : null;
    }

    public String toJson(OperationOutcome oo) {
        return ctx.newJsonParser().encodeResourceToString(oo);
    }

    static String redact(String s) {
        return s == null ? "" : QUOTED.matcher(s).replaceAll("'***'");
    }
}
