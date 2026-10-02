package com.example.hdip.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback converter; use as %mask (replaces %msg) after registering a conversionRule. */
public class PhiMaskingConverter extends ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return PhiMasker.mask(event.getFormattedMessage());
    }
}
