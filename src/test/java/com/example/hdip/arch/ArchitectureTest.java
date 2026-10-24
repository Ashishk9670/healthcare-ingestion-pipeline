package com.example.hdip.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.conditions.ArchPredicates.are;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

/**
 * Architecture rules: package boundaries, controller/repository separation and PHI-safe logging.
 * Production classes only (tests are excluded).
 */
@AnalyzeClasses(packages = "com.example.hdip", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    private static final String BASE = "com.example.hdip.";

    // ------------------------------------------------------------------ package boundaries

    /** Pure building blocks: no dependency on the web, persistence, messaging or feature packages. */
    @ArchTest
    static final ArchRule leafPackagesDoNotDependOnApplicationLayers = noClasses()
            .that().resideInAnyPackage(BASE + "model..", BASE + "crypto..", BASE + "deid..", BASE + "logging..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    BASE + "api..", BASE + "store..", BASE + "kafka..", BASE + "graphql..", BASE + "export..",
                    BASE + "consent..", BASE + "security..", BASE + "extract..", BASE + "enrich..", BASE + "config..",
                    BASE + "audit..", BASE + "ratelimit..", BASE + "synthetic..")
            .as("model, crypto, deid and logging must not depend on application layers");

    @ArchTest
    static final ArchRule parsersOnlyDependOnModel = noClasses()
            .that().resideInAnyPackage(BASE + "parse..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    BASE + "api..", BASE + "store..", BASE + "kafka..", BASE + "graphql..", BASE + "export..",
                    BASE + "consent..", BASE + "security..", BASE + "extract..", BASE + "enrich..", BASE + "audit..")
            .as("parse must only depend on model (and the JDK / libraries)");

    @ArchTest
    static final ArchRule deidentificationIsSelfContained = noClasses()
            .that().resideInAnyPackage(BASE + "deid..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "store..", BASE + "consent..", BASE + "export..", BASE + "api..", BASE + "kafka..")
            .as("deid must stay free of persistence, consent and transport concerns");

    @ArchTest
    static final ArchRule nothingDependsOnTheWebLayers = noClasses()
            .that().resideOutsideOfPackages(BASE + "api..", BASE + "graphql..", BASE + "export..", BASE + "consent..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "api..", BASE + "graphql..", BASE + "export..")
            .as("only web-facing packages may use the api/graphql/export packages");

    @ArchTest
    static final ArchRule storeDoesNotDependOnTransport = noClasses()
            .that().resideInAnyPackage(BASE + "store..", BASE + "audit..", BASE + "consent..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "api..", BASE + "graphql..", BASE + "export..", BASE + "kafka..")
            .as("persistence-side packages must not depend on web or messaging packages");

    // ------------------------------------------------------------------ controllers vs repositories

    private static final DescribedPredicate<JavaClass> CONTROLLERS = DescribedPredicate.describe("controllers",
            c -> c.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    || c.isAnnotatedWith("org.springframework.stereotype.Controller")
                    || c.isAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice"));

    @ArchTest
    static final ArchRule controllersDoNotAccessRepositories = noClasses().that(CONTROLLERS)
            .should().dependOnClassesThat(assignableTo("org.springframework.data.repository.Repository"))
            .as("controllers must go through services, never directly to repositories");

    @ArchTest
    static final ArchRule controllersDoNotExposeEntities = noClasses().that(CONTROLLERS)
            .should().dependOnClassesThat(DescribedPredicate.describe("PHI-bearing JPA entities",
                    (JavaClass c) -> c.isAnnotatedWith("jakarta.persistence.Entity") && !c.getPackageName().equals(BASE + "audit")))
            .as("controllers must not handle PHI-bearing JPA entities (audit entries hold no PHI and are exempt)");

    @ArchTest
    static final ArchRule repositoriesAreOnlyUsedBelowControllers = noClasses()
            .that().resideInAnyPackage(BASE + "api..", BASE + "graphql..", BASE + "security..", BASE + "config..")
            .should().dependOnClassesThat(assignableTo("org.springframework.data.repository.Repository"))
            .as("web, security and config packages must not use repositories directly");

    // ------------------------------------------------------------------ logging hygiene

    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule loggersArePrivateStaticFinal = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields()
            .that().haveRawType("org.slf4j.Logger")
            .should().bePrivate().andShould().beStatic().andShould().beFinal();

    /** Accessors that return PHI (or free text that may contain PHI); never allowed in a method that logs. */
    private static final Set<String> PHI_ACCESSORS = Set.of(
            "patient", "notes", "familyName", "givenName", "ssn", "birthDate", "mrn", "payload", "valueText",
            "getFamilyName", "getGivenName", "getSsn", "getBirthDate", "getMrn", "getPayload",
            "getFamilyNameEnc", "getGivenNameEnc", "getSsnEnc", "getBirthDateEnc", "getMrnEnc");
    private static final Set<String> PHI_TYPES = Set.of(
            "com.example.hdip.model.CanonicalPatient", "com.example.hdip.store.PatientEntity",
            "com.example.hdip.model.CanonicalObservation");

    /**
     * Methods that call SLF4J must not touch PHI-bearing accessors or types. Only already-masked/opaque values
     * (message ids, formats, counts) may be logged; anything else goes through {@code PhiMasker}.
     */
    @ArchTest
    static final ArchRule loggingMethodsDoNotTouchPhiFields = methods()
            .that(are(callSlf4j()))
            .should(notReadPhi())
            .as("methods that log must not read PHI fields (use PhiMasker or log opaque ids only)");

    @ArchTest
    static final ArchRule loggingPackageIsFreeOfApplicationCode = noClasses()
            .that().resideInAnyPackage(BASE + "logging..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "store..", BASE + "api..", BASE + "kafka..", BASE + "graphql..")
            .as("the masking utilities themselves must not depend on application code");

    private static DescribedPredicate<JavaMethod> callSlf4j() {
        return DescribedPredicate.describe("call org.slf4j.Logger",
                m -> m.getMethodCallsFromSelf().stream().anyMatch(c -> c.getTargetOwner().isAssignableTo("org.slf4j.Logger")));
    }

    private static ArchCondition<JavaMethod> notReadPhi() {
        return new ArchCondition<>("not read PHI accessors or PHI types") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaCall<?> call : method.getMethodCallsFromSelf()) {
                    String owner = call.getTargetOwner().getFullName();
                    boolean phiType = PHI_TYPES.contains(owner);
                    boolean phiName = PHI_ACCESSORS.contains(call.getName());
                    if (phiType || phiName) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " logs but calls " + owner + "." + call.getName()
                                        + " at " + call.getSourceCodeLocation()));
                    }
                }
            }
        };
    }
}
