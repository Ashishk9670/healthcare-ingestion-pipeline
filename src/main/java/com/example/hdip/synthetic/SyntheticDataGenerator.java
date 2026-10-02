package com.example.hdip.synthetic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates entirely FAKE patients: invented names, MRNs prefixed "SYN-", SSNs in the 900-999 area range
 * (never issued to real people). SYNTHETIC DATA ONLY.
 */
@Component
public class SyntheticDataGenerator {
    private static final String[] FAMILY = {"Testerson", "Faux", "Mockwell", "Sampleton", "Dummerly", "Placeholder", "Fakename", "Syntheo"};
    private static final String[] GIVEN = {"Alex", "Jordan", "Taylor", "Morgan", "Casey", "Riley", "Quinn", "Avery"};
    private static final String[][] DX = {{"E11.9", "Type 2 diabetes mellitus without complications"},
            {"I10", "Essential (primary) hypertension"}, {"J45.909", "Unspecified asthma, uncomplicated"},
            {"C50.911", "Malignant neoplasm of unspecified site of right female breast"},
            {"N18.3", "Chronic kidney disease, stage 3"}};
    private static final String[][] OBS = {{"4548-4", "Hemoglobin A1c", "%", "4.5", "9.5"},
            {"8480-6", "Systolic blood pressure", "mm[Hg]", "95", "180"}, {"2339-0", "Glucose", "mg/dL", "70", "260"}};
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Random random;
    private final ObjectMapper mapper;

    @Autowired
    public SyntheticDataGenerator(ObjectMapper mapper) {
        this(mapper, new Random());
    }

    public SyntheticDataGenerator(ObjectMapper mapper, Random random) {
        this.mapper = mapper;
        this.random = random;
    }

    public record SyntheticPatient(String mrn, String family, String given, LocalDate dob, String gender, String ssn,
                                   List<String[]> diagnoses, List<String[]> observations) {
    }

    public SyntheticPatient patient() {
        String mrn = "SYN-" + (100000 + random.nextInt(900000));
        String ssn = String.format("9%02d-%02d-%04d", random.nextInt(100), 1 + random.nextInt(99), random.nextInt(10000));
        LocalDate dob = LocalDate.now().minusDays(365L * 5 + random.nextInt(365 * 85));
        List<String[]> dx = new ArrayList<>();
        int n = random.nextInt(3);
        for (int i = 0; i < n; i++) {
            String[] d = DX[random.nextInt(DX.length)];
            if (dx.stream().noneMatch(x -> x[0].equals(d[0]))) dx.add(d);
        }
        List<String[]> obs = new ArrayList<>();
        for (String[] o : OBS) {
            double lo = Double.parseDouble(o[3]), hi = Double.parseDouble(o[4]);
            String v = String.format("%.1f", lo + random.nextDouble() * (hi - lo));
            obs.add(new String[]{o[0], o[1], o[2], v});
        }
        return new SyntheticPatient(mrn, FAMILY[random.nextInt(FAMILY.length)], GIVEN[random.nextInt(GIVEN.length)], dob,
                random.nextBoolean() ? "female" : "male", ssn, dx, obs);
    }

    public String hl7Message() {
        return hl7Message(patient());
    }

    public String hl7Message(SyntheticPatient p) {
        String now = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        List<String> segs = new ArrayList<>();
        segs.add("MSH|^~\\&|SYNTH|SYNTH_FAC|HDIP|HDIP_FAC|" + now + "||ORU^R01|" + UUID.randomUUID() + "|P|2.5");
        String[] pid = new String[20];
        java.util.Arrays.fill(pid, "");
        pid[0] = "PID";
        pid[1] = "1";
        pid[3] = p.mrn() + "^^^SYNTH^MR";
        pid[5] = p.family() + "^" + p.given();
        pid[7] = p.dob().format(DateTimeFormatter.BASIC_ISO_DATE);
        pid[8] = p.gender().equals("female") ? "F" : "M";
        pid[19] = p.ssn();
        segs.add(String.join("|", pid));
        int i = 1;
        for (String[] d : p.diagnoses()) {
            segs.add("DG1|" + i++ + "||" + d[0] + "^" + d[1] + "^I10");
        }
        i = 1;
        for (String[] o : p.observations()) {
            String[] obx = new String[15];
            java.util.Arrays.fill(obx, "");
            obx[0] = "OBX";
            obx[1] = String.valueOf(i++);
            obx[2] = "NM";
            obx[3] = o[0] + "^" + o[1] + "^LN";
            obx[5] = o[3];
            obx[6] = o[2];
            obx[14] = now;
            segs.add(String.join("|", obx));
        }
        return String.join("\r", segs);
    }

    public String fhirBundle() {
        return fhirBundle(patient());
    }

    public String fhirBundle(SyntheticPatient p) {
        ObjectNode bundle = mapper.createObjectNode();
        bundle.put("resourceType", "Bundle").put("type", "collection").put("id", UUID.randomUUID().toString());
        ArrayNode entries = bundle.putArray("entry");

        ObjectNode pat = entries.addObject().putObject("resource");
        pat.put("resourceType", "Patient");
        ArrayNode ids = pat.putArray("identifier");
        ids.addObject().put("system", "urn:synthetic:mrn").put("value", p.mrn());
        ids.addObject().put("system", "urn:synthetic:ssn").put("value", p.ssn());
        ObjectNode name = pat.putArray("name").addObject();
        name.put("family", p.family());
        name.putArray("given").add(p.given());
        pat.put("gender", p.gender());
        pat.put("birthDate", p.dob().toString());

        for (String[] d : p.diagnoses()) {
            ObjectNode c = entries.addObject().putObject("resource");
            c.put("resourceType", "Condition");
            c.putObject("code").putArray("coding").addObject()
                    .put("system", "http://hl7.org/fhir/sid/icd-10-cm").put("code", d[0]).put("display", d[1]);
        }
        for (String[] o : p.observations()) {
            ObjectNode ob = entries.addObject().putObject("resource");
            ob.put("resourceType", "Observation");
            ob.putObject("code").putArray("coding").addObject()
                    .put("system", "http://loinc.org").put("code", o[0]).put("display", o[1]);
            ob.putObject("valueQuantity").put("value", Double.parseDouble(o[3])).put("unit", o[2]);
            ob.put("effectiveDateTime", java.time.OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString());
        }
        return bundle.toString();
    }
}
