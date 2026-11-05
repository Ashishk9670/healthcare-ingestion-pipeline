package com.example.hdip.extract;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic dictionary/regex extractor (no external calls). Handles simple negation ("no evidence of ...",
 * "denies ...") and family-history phrasing within a sentence.
 */
@Component
@ConditionalOnProperty(name = "app.extraction.extractor", havingValue = "rule-based", matchIfMissing = true)
public class RuleBasedExtractor implements Extractor {
    /** term -> ICD-10-CM category prefix. Longer terms first so they win over their substrings. */
    private static final Map<String, String> DIAGNOSES = new LinkedHashMap<>();
    private static final List<String> MEDICATIONS = List.of("metformin", "insulin glargine", "lisinopril", "amlodipine",
            "atorvastatin", "albuterol", "tamoxifen", "letrozole", "carboplatin", "cisplatin", "pembrolizumab");
    private static final Pattern STAGE = Pattern.compile("\\bstage\\s+(0|iv|iii|ii|i|[1-4])([abc][1-3]?)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[.;\\n\\r]+");
    private static final Pattern NEGATION = Pattern.compile(
            "\\b(no|denies|denied|without|negative for|ruled out|no evidence of|no history of|family history of|r/o)\\b");

    static {
        DIAGNOSES.put("type 2 diabetes mellitus", "E11");
        DIAGNOSES.put("type 2 diabetes", "E11");
        DIAGNOSES.put("essential hypertension", "I10");
        DIAGNOSES.put("hypertension", "I10");
        DIAGNOSES.put("asthma", "J45");
        DIAGNOSES.put("breast cancer", "C50");
        DIAGNOSES.put("lung cancer", "C34");
        DIAGNOSES.put("colon cancer", "C18");
        DIAGNOSES.put("chronic kidney disease", "N18");
        DIAGNOSES.put("heart failure", "I50");
        DIAGNOSES.put("copd", "J44");
    }

    @Override
    public List<ExtractedFact> extract(String noteText) {
        if (noteText == null || noteText.isBlank()) return List.of();
        Map<String, ExtractedFact> out = new LinkedHashMap<>();
        for (String sentence : SENTENCE_SPLIT.split(noteText)) {
            String s = sentence.toLowerCase(Locale.ROOT);
            if (s.isBlank()) continue;
            String covered = ""; // sentence text already matched by a longer diagnosis term
            for (var e : DIAGNOSES.entrySet()) {
                int idx = indexOfWord(s, e.getKey());
                if (idx < 0 || negated(s, idx) || covered.contains(e.getKey())) continue;
                covered += " " + e.getKey();
                out.putIfAbsent("D:" + e.getValue(), new ExtractedFact(ExtractedFact.Kind.DIAGNOSIS, e.getKey(), e.getValue()));
            }
            for (String med : MEDICATIONS) {
                int idx = indexOfWord(s, med);
                if (idx >= 0 && !negated(s, idx)) out.putIfAbsent("M:" + med, new ExtractedFact(ExtractedFact.Kind.MEDICATION, med, null));
            }
            Matcher m = STAGE.matcher(s);
            while (m.find()) {
                if (negated(s, m.start())) continue;
                String stage = (m.group(1) + (m.group(2) == null ? "" : m.group(2))).toUpperCase(Locale.ROOT);
                out.putIfAbsent("S:" + stage, new ExtractedFact(ExtractedFact.Kind.STAGE, stage, null));
            }
        }
        return new ArrayList<>(out.values());
    }

    private static int indexOfWord(String s, String term) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(term) + "\\b").matcher(s);
        return m.find() ? m.start() : -1;
    }

    private static boolean negated(String sentence, int termStart) {
        return NEGATION.matcher(sentence.substring(0, termStart)).find();
    }
}
