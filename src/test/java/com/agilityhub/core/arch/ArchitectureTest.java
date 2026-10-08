package com.agilityhub.core.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.agilityhub.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule E3_T02_signupDomainHasNoSpringOrMongo =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                    .that().resideInAnyPackage("..clubs.signup.domain..", "..clubs.scheduling.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.mongodb..", "org.bson..");

    /**
     * E8-T08 round 2 (E92): CVE-2026-47884 (Spring MVC `XsltView`, fixed only in Framework 7) is suppressed in
     * `bin/security-scan-suppressions.json` because the api renders no server-side views; this keeps that true.
     */
    @ArchTest
    static final ArchRule E8_T08_noXsltServerSideViews =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web.servlet.view.xslt..")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.servlet.ViewResolver");

    @ArchTest
    static final ArchRule E0_T01_domainHasNoWebDataSecurityOrServletDependencies =
            ArchitectureRules.DOMAIN_INDEPENDENCE;

    @ArchTest
    static final ArchRule E0_T01_contextsHaveNoCycles = ArchitectureRules.CONTEXT_CYCLES;

    @ArchTest
    static final ArchRule E0_T01_crossContextAccessUsesApplication = ArchitectureRules.CONTEXT_BOUNDARIES;

    @ArchTest
    static final ArchRule E0_T01_sharedDependsOnNoOtherContext = ArchitectureRules.SHARED_INDEPENDENCE;

    /**
     * T-15-07: no Instant.now()/LocalDate.now()/…/System.currentTimeMillis() outside the Clock beans, and no
     * Clock.systemUTC()/systemDefaultZone()/system(zone)/new Date() outside the clock configuration.
     */
    @ArchTest
    static final ArchRule E5_T01_timeComesFromTheClock = ArchitectureRules.TIME_FROM_CLOCK;

    @ArchTest
    static final ArchRule E5_T09_onlyDemoSeedClassesRunAsTheSeedActor = ArchitectureRules.DEMO_SEED_ACTOR;

    @ArchTest
    static final ArchRule E5_T09_onlyTheBookingWritersSetTheClassCounters = ArchitectureRules.COUNTER_WRITERS;

    @ArchTest
    static final ArchRule E5_T09_consumerEnvelopesAreNotPublishable = ArchitectureRules.CONSUMER_ENVELOPES;

    @ArchTest
    static final ArchRule E5_T06_onlyTheDemoSeedMovesTheBookingTime = ArchitectureRules.BOOKING_TIME_OVERRIDE;

    @ArchTest
    static final ArchRule E6_T01_bookingsNeverImportsFollowup = ArchitectureRules.BOOKINGS_WITHOUT_FOLLOWUP;

    @ArchTest
    static final ArchRule E6_T01_platformImportsNoClubsPackage = ArchitectureRules.PLATFORM_WITHOUT_CLUBS;

    @ArchTest
    static final ArchRule E6_T03_followupImportsNeitherCensusNorBookings = ArchitectureRules.FOLLOWUP_WITHOUT_CENSUS_OR_BOOKINGS;

    @ArchTest
    static final ArchRule E7_T01_messagingImportsNoOtherClubsContext = ArchitectureRules.MESSAGING_WITHOUT_OTHER_CLUBS;

    @ArchTest
    static final ArchRule E8_T01_paymentsReachesOnlyCatalogsAndCensusApplications = ArchitectureRules.PAYMENTS_CLUB_DEPENDENCIES;

    @ArchTest
    static final ArchRule E8_T01_censusReachesPaymentsAndBookingsOnlyThroughPorts = ArchitectureRules.CENSUS_THROUGH_PORTS;

    @ArchTest
    static final ArchRule E8_T02_T_12_12_noPaymentsRepositoryUpdatesAnInvoiceOrItsLines = ArchitectureRules.INVOICE_LINES_IMMUTABLE;
}
