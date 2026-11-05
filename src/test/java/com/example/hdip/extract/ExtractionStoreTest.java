package com.example.hdip.extract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExtractionStoreTest {
    @Mock ClinicalExtractionRepository repo;

    @Test
    void skipsFactsAlreadyStored() {
        var a = new ExtractedFact(ExtractedFact.Kind.STAGE, "IV", null);
        var b = new ExtractedFact(ExtractedFact.Kind.MEDICATION, "metformin", null);
        when(repo.existsByMrnHashAndKindAndValue("h", a.kind(), "IV")).thenReturn(true);
        when(repo.existsByMrnHashAndKindAndValue("h", b.kind(), "metformin")).thenReturn(false);
        int n = new ExtractionStore(repo).persist(new ExtractionEvent("m", "h", List.of(a, b), Instant.now()));
        assertThat(n).isEqualTo(1);
        verify(repo, times(1)).save(any(ClinicalExtractionEntity.class));
    }
}
