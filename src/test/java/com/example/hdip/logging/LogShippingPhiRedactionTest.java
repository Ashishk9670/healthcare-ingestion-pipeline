package com.example.hdip.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the log pipeline never ships unmasked PHI, in two independent layers, using synthetic identifiers only:
 * 1) the application's real logback layout (logback-spring.xml) masks messages and exception text;
 * 2) the Alloy redaction stages (docker/observability/alloy/config.alloy) redact PHI-looking tokens even if layer 1 missed them.
 * Alloy uses RE2; the expressions are simple enough to evaluate with java.util.regex.
 */
class LogShippingPhiRedactionTest {
    static final Path ALLOY = Path.of("docker", "observability", "alloy", "config.alloy");
    static final Path LOGBACK = Path.of("src", "main", "resources", "logback-spring.xml");

    static final String SSN = "900-12-3456";
    static final String MRN = "SYN-482913";
    static final String FAMILY = "Testerson";
    static final String GIVEN = "Quincy";

    private static List<Pattern> alloyExpressions() throws IOException {
        String cfg = Files.readString(ALLOY, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("expression\\s*=\\s*`([^`]+)`").matcher(cfg);
        List<Pattern> out = new ArrayList<>();
        while (m.find()) out.add(Pattern.compile(m.group(1)));
        return out;
    }

    /** loki.process stage.replace: the text matched by the (single) capture group is replaced. */
    private static String redact(String line, List<Pattern> stages) {
        String cur = line;
        for (Pattern p : stages) {
            Matcher m = p.matcher(cur);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (m.find()) {
                sb.append(cur, last, m.start(1)).append("***");
                last = m.end(1);
            }
            cur = sb.append(cur, last, cur.length()).toString();
        }
        return cur;
    }

    private static void assertClean(String text) {
        assertThat(text).doesNotContain(SSN, MRN, FAMILY, GIVEN);
    }

    @Test
    void alloyConfigHasRedactionStagesWithOneCaptureGroupEach() throws IOException {
        List<Pattern> stages = alloyExpressions();
        assertThat(stages).hasSizeGreaterThanOrEqualTo(4);
        for (Pattern p : stages) assertThat(p.matcher("").groupCount()).as(p.pattern()).isEqualTo(1);
        String cfg = Files.readString(ALLOY, StandardCharsets.UTF_8);
        assertThat(cfg).as("only the app container is shipped").contains("regex         = \"app\"");
        assertThat(cfg).as("no log content may be promoted to labels").doesNotContain("stage.labels").doesNotContain("stage.structured_metadata");
        assertThat(cfg.indexOf("loki.process \"redact_phi\"")).isLessThan(cfg.indexOf("loki.write \"default\""));
        assertThat(cfg).contains("forward_to = [loki.process.redact_phi.receiver]");
    }

    @Test
    void alloyStagesRedactPhiEvenIfTheApplicationMaskerMissedIt() throws IOException {
        List<Pattern> stages = alloyExpressions();
        List<String> rawLines = List.of(
                "Failed to persist ssn " + SSN + " for patient",
                "lookup mrn=" + MRN + " failed",
                "token " + MRN + " not found",
                "payload PID|1||" + MRN + "^^^S^MR||" + FAMILY + "^" + GIVEN + "||19700101|F|||||||||||" + SSN,
                "{\"familyName\":\"" + FAMILY + "\",\"givenName\":\"" + GIVEN + "\",\"ssn\":\"" + SSN + "\"}",
                "family_name=" + FAMILY + " given_name=" + GIVEN + " dob=1970-01-01");
        for (String line : rawLines) {
            assertClean(redact(line, stages));
        }
        assertThat(redact("Normalized message 4b1f (HL7V2)", stages)).as("benign lines are untouched")
                .isEqualTo("Normalized message 4b1f (HL7V2)");
    }

    @Test
    void realApplicationLogLayoutMasksMessagesAndExceptionsBeforeShipping() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            JoranConfigurator cfg = new JoranConfigurator();
            cfg.setContext(ctx);
            cfg.doConfigure(LOGBACK.toFile());
            var log = ctx.getLogger("com.example.hdip.test");
            log.info("Persisting ssn {} mrn={} name={}", SSN, MRN, FAMILY);
            log.warn("PID|1||" + MRN + "^^^S^MR||" + FAMILY + "^" + GIVEN + "||19700101|F|||||||||||" + SSN);
            log.error("Failed for " + MRN, new IllegalStateException("bad record ssn=" + SSN + " familyName=" + FAMILY));
        } finally {
            ctx.stop();
            System.setOut(original);
        }
        String shipped = buf.toString(StandardCharsets.UTF_8);
        assertThat(shipped).as("the layout wrote something").contains("Persisting");
        assertClean(shipped);
        // and the Alloy layer leaves already-masked output clean as well
        assertClean(redact(shipped, alloyExpressions()));
    }
}
