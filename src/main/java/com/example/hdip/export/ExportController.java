package com.example.hdip.export;

import com.example.hdip.audit.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * De-identified bulk export (ANALYST only, enforced in SecurityConfig). The export is written synchronously on the
 * request thread so the authenticated principal is available to the audit entry.
 */
@RestController
@RequestMapping("/api/v1/export")
public class ExportController {
    static final String NDJSON = "application/fhir+ndjson";
    static final int MAX_PATIENTS = 10_000;

    private final FhirNdjsonExportService export;
    private final AuditService audit;

    public ExportController(FhirNdjsonExportService export, AuditService audit) {
        this.export = export;
        this.audit = audit;
    }

    @GetMapping("/fhir/ndjson")
    public void exportNdjson(@RequestParam(defaultValue = "1000") int limit, HttpServletResponse response) throws IOException {
        int n = Math.max(1, Math.min(limit, MAX_PATIENTS));
        audit.record("EXPORT_FHIR_NDJSON_START", "Export", null, "method=safe-harbor limit=" + n); // before any data leaves
        response.setStatus(200);
        response.setContentType(NDJSON);
        response.setHeader("Cache-Control", "no-store");
        var result = export.export(response.getOutputStream(), n);
        audit.record("EXPORT_FHIR_NDJSON_DONE", "Export", null,
                "method=safe-harbor patients=" + result.patients() + " resources=" + result.resources() + " limit=" + n);
    }
}
