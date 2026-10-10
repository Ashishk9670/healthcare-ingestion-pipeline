package com.example.hdip.observability;

import com.example.hdip.logging.PhiMasker;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Defence in depth: masks PHI-looking patterns in every observation (span/metric) key value before export. */
@Component
public class PhiSafeObservationFilter implements ObservationFilter {
    @Override
    public Observation.Context map(Observation.Context context) {
        List<KeyValue> all = new ArrayList<>();
        context.getLowCardinalityKeyValues().stream().forEach(all::add);
        context.getHighCardinalityKeyValues().stream().forEach(all::add);
        for (KeyValue kv : all) {
            String masked = PhiMasker.mask(kv.getValue());
            if (!masked.equals(kv.getValue())) {
                context.removeLowCardinalityKeyValue(kv.getKey());
                context.removeHighCardinalityKeyValue(kv.getKey());
                context.addHighCardinalityKeyValue(KeyValue.of(kv.getKey(), masked));
            }
        }
        return context;
    }
}
