package com.example.hdip.export;

import com.example.hdip.consent.ConsentScope;
import com.example.hdip.consent.ConsentService;
import com.example.hdip.deid.SafeHarborDeidentifier;
import com.example.hdip.store.DiagnosisEntity;
import com.example.hdip.store.ObservationEntity;
import com.example.hdip.store.PatientEntity;
import com.example.hdip.store.PatientRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bulk FHIR R4 NDJSON export (Patient, Condition, Observation), de-identified with the Safe Harbor method. Resources are
 * built from stored data of patients whose EXPORT consent permits it, and then passed through {@link SafeHarborDeidentifier} (defence in depth: nothing is emitted
 * that has not been through the de-identifier). Patient resource ids are the random surrogate UUIDs of the store.
 */
@Service
public class FhirNdjsonExportService {
    static final int PAGE_SIZE = 100;

    public record ExportResult(int patients, int resources) {
    }

    private final PatientRepository patients;
    private final ConsentService consent;
    private final SafeHarborDeidentifier deid;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public FhirNdjsonExportService(PatientRepository patients, ConsentService consent, SafeHarborDeidentifier deid, ObjectMapper mapper,
                                   PlatformTransactionManager txManager) {
        this.consent = consent;
        this.patients = patients;
        this.deid = deid;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setReadOnly(true);
    }

    public ExportResult export(OutputStream out, int maxPatients) throws IOException {
        int exportedPatients = 0;
        int resources = 0;
        for (int page = 0; exportedPatients < maxPatients; page++) {
            final int pageNo = page;
            final int remaining = maxPatients - exportedPatients;
            List<String> lines = tx.execute(st -> linesForPage(pageNo, remaining));
            if (lines == null || lines.isEmpty()) break;
            for (String l : lines) {
                out.write(l.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            }
            resources += lines.size();
            exportedPatients += (int) lines.stream().filter(l -> l.startsWith("{\"resourceType\":\"Patient\"")).count();
            out.flush();
        }
        return new ExportResult(exportedPatients, resources);
    }

    private List<String> linesForPage(int page, int remaining) {
        var slice = patients.findAll(consent.permitted(ConsentScope.EXPORT), PageRequest.of(page, PAGE_SIZE, Sort.by("createdAt", "id")));
        List<String> lines = new ArrayList<>();
        int count = 0;
        for (PatientEntity p : slice.getContent()) {
            if (count++ >= remaining) break;
            lines.add(emit(patient(p)));
            for (DiagnosisEntity d : p.getDiagnoses()) lines.add(emit(condition(p, d)));
            for (ObservationEntity o : p.getObservations()) lines.add(emit(observation(p, o)));
        }
        return lines;
    }

    private String emit(ObjectNode raw) {
        ObjectNode safe = (ObjectNode) deid.deidentify(raw);
        // mark as anonymized (set after de-identification, which drops incoming meta)
        ObjectNode meta = safe.putObject("meta");
        meta.putArray("security").addObject().put("system", "http://terminology.hl7.org/CodeSystem/v3-ObservationValue")
                .put("code", "ANONYED");
        return safe.toString();
    }

    private ObjectNode patient(PatientEntity p) {
        ObjectNode o = mapper.createObjectNode();
        o.put("resourceType", "Patient").put("id", p.getId().toString());
        if (p.getGender() != null) o.put("gender", p.getGender());
        if (p.getBirthDate() != null) o.put("birthDate", p.getBirthDate()); // generalised to year / dropped if >89
        return o;
    }

    private ObjectNode condition(PatientEntity p, DiagnosisEntity d) {
        ObjectNode o = mapper.createObjectNode();
        o.put("resourceType", "Condition").put("id", d.getId().toString());
        o.putObject("subject").put("reference", "Patient/" + p.getId());
        ObjectNode c = o.putObject("code").putArray("coding").addObject();
        if (d.getSystem() != null) c.put("system", d.getSystem());
        if (d.getCode() != null) c.put("code", d.getCode());
        if (d.getDisplay() != null) c.put("display", d.getDisplay());
        return o;
    }

    private ObjectNode observation(PatientEntity p, ObservationEntity ob) {
        ObjectNode o = mapper.createObjectNode();
        o.put("resourceType", "Observation").put("id", ob.getId().toString()).put("status", "final");
        o.putObject("subject").put("reference", "Patient/" + p.getId());
        ObjectNode c = o.putObject("code").putArray("coding").addObject();
        if (ob.getSystem() != null) c.put("system", ob.getSystem());
        if (ob.getCode() != null) c.put("code", ob.getCode());
        if (ob.getDisplay() != null) c.put("display", ob.getDisplay());
        if (ob.getValueNumeric() != null) {
            ObjectNode q = o.putObject("valueQuantity").put("value", ob.getValueNumeric());
            if (ob.getUnit() != null) q.put("unit", ob.getUnit());
        } else if (ob.getValueText() != null) {
            o.put("valueString", ob.getValueText());
        }
        if (ob.getEffectiveAt() != null) o.put("effectiveDateTime", ob.getEffectiveAt().toString());
        return o;
    }
}
