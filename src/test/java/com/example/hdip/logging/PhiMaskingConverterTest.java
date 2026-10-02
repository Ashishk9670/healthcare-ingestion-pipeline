package com.example.hdip.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class PhiMaskingConverterTest {
    @Test
    void convertsFormattedMessage() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = ctx.getLogger("t");
        LoggingEvent e = new LoggingEvent("f", logger, Level.INFO, "ssn={} name={}", null, new Object[]{"900-11-2222", "x"});
        assertThat(new PhiMaskingConverter().convert(e)).doesNotContain("900-11-2222");
    }
}
