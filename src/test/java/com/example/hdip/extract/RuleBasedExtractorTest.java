package com.example.hdip.extract;

import org.junit.jupiter.api.Test;

import static com.example.hdip.extract.ExtractedFact.Kind.*;
import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedExtractorTest {
    final Extractor x = new RuleBasedExtractor();

    @Test
    void extractsDiagnosisMedicationAndStage() {
        var facts = x.extract("Assessment: breast cancer, stage IIB. Current therapy: tamoxifen 20 mg daily.");
        assertThat(facts).contains(new ExtractedFact(DIAGNOSIS, "breast cancer", "C50"),
                new ExtractedFact(MEDICATION, "tamoxifen", null), new ExtractedFact(STAGE, "IIB", null));
    }

    @Test
    void negatedAndFamilyHistoryAreSkipped() {
        var facts = x.extract("No evidence of heart failure. Denies asthma. Family history of lung cancer; takes metformin.");
        assertThat(facts).extracting(ExtractedFact::kind, ExtractedFact::value).containsExactly(org.assertj.core.groups.Tuple.tuple(MEDICATION, "metformin"));
    }

    @Test
    void longerTermWinsAndDedupes() {
        var facts = x.extract("Type 2 diabetes mellitus. Also type 2 diabetes noted again; essential hypertension.");
        assertThat(facts).filteredOn(f -> f.kind() == DIAGNOSIS).extracting(ExtractedFact::code).containsExactly("E11", "I10");
    }

    @Test
    void numericStageAndCaseInsensitive() {
        assertThat(x.extract("CHRONIC KIDNEY DISEASE STAGE 3")).contains(new ExtractedFact(DIAGNOSIS, "chronic kidney disease", "N18"),
                new ExtractedFact(STAGE, "3", null));
    }

    @Test
    void wordBoundariesAndEmptyInput() {
        assertThat(x.extract("The patient is stable in clinic")).isEmpty(); // 'stage in' must not match
        assertThat(x.extract("   ")).isEmpty();
        assertThat(x.extract(null)).isEmpty();
    }

    @Test
    void deterministic() {
        String n = "Breast cancer stage IV on letrozole and carboplatin.";
        assertThat(x.extract(n)).isEqualTo(x.extract(n));
    }
}
