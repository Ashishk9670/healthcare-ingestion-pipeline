package com.example.hdip.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Masks PHI that may appear in exception messages; use as %maskEx. */
public class PhiMaskingThrowableConverter extends ThrowableProxyConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return PhiMasker.mask(super.convert(event));
    }
}
