package com.example.hdip.api;

import com.example.hdip.fhir.FhirValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    /** FHIR validation failures are returned as an OperationOutcome (values redacted, never payload content). */
    @ExceptionHandler(FhirValidationException.class)
    ResponseEntity<String> fhirInvalid(FhirValidationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.parseMediaType("application/fhir+json"))
                .body(e.operationOutcomeJson());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        // message is a fixed validation text, never payload content
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
