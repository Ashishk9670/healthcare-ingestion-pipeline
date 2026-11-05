package com.example.hdip.extract;

import java.util.List;

/**
 * Pluggable clinical-note extraction. The default implementation is a deterministic rule-based one; an ML/LLM-backed
 * implementation can replace it by providing a bean and setting {@code app.extraction.extractor} to something other than
 * {@code rule-based}. Implementations must be side-effect free and must not log note text.
 */
public interface Extractor {
    List<ExtractedFact> extract(String noteText);
}
