package com.agilityhub.core.arch;

import com.agilityhub.core.clubs.bookings.application.BookingContext;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.shared.application.DemoSeedActor;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** E5-T09 (E4-T05 review #2 and #8, E5-T05 review #10): the new ArchUnit rules reject what they guard and accept the allowed callers. */
class SeedAndWriterRulesTest {
    static final class RunsAsTheSeedActor {
        Object run() { return DemoSeedActor.as("account", "ADMIN", () -> "work"); }
    }
    static final class WritesTheClassCounters {
        Object run(ClassSessionBookingAccess classes) { return classes.counters("class", 1, 0, ClassSessionBookingAccess.LowAlert.KEEP); }
    }
    /** E5-T14 (review E5-T09 #7): a method reference hands the writer to someone else; it is an access too. */
    static final class ReferencesTheClassCounters {
        Object run(ClassSessionBookingAccess classes) {
            ClassSessionBookingAccessCounters writer = classes::counters; return writer.write("class", 1, 0, ClassSessionBookingAccess.LowAlert.KEEP);
        }
    }
    interface ClassSessionBookingAccessCounters { Object write(String id, int booked, int waiting, ClassSessionBookingAccess.LowAlert alert); }
    static final class MovesTheBookingTime {
        Object run(BookingContext context) { return context.asOf(Instant.EPOCH, () -> "work"); }
    }
    record SneakyForeignEvent(String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt, Map<String, Object> payload,
            String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent { }
    record SneakyExternalEvent(String type, String clubId, String aggregateType, String aggregateId, Instant occurredAt, Map<String, Object> payload,
            String actorAccountId, String impersonatedMemberId, Origin origin) implements DomainEvent { }

    @Test void E5_T09_onlyDemoSeedClassesMayRunAsTheSeedActor() {
        var result = ArchitectureRules.DEMO_SEED_ACTOR.evaluate(new ClassFileImporter().importClasses(RunsAsTheSeedActor.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(line -> line.contains("DemoSeedActor.as"));
        // Allowed: a top-level Demo* class and its nested class; a class that does not touch the actor is no violation either.
        assertThatCode(() -> ArchitectureRules.DEMO_SEED_ACTOR.check(new ClassFileImporter().importClasses(DemoRuleFixtureSeeder.class,
                DemoRuleFixtureSeeder.Nested.class, WritesTheClassCounters.class))).doesNotThrowAnyException();
    }

    @Test void E5_T09_classCountersAreWrittenOnlyByTheBookingContext() {
        var result = ArchitectureRules.COUNTER_WRITERS.evaluate(new ClassFileImporter().importClasses(WritesTheClassCounters.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(line -> line.contains("ClassSessionBookingAccess.counters"));
    }

    @Test void E5_T14_onlyTheNamedS08WriterSetsTheCountersAndAMethodReferenceIsCaught() {
        var reference = ArchitectureRules.COUNTER_WRITERS.evaluate(new ClassFileImporter().importClasses(ReferencesTheClassCounters.class));
        assertThat(reference.hasViolation()).isTrue();
        assertThat(reference.getFailureReport().getDetails()).anyMatch(line -> line.contains("references") &&line.contains("ClassSessionBookingAccess.counters"));
        // Other S08 and S06 classes are no longer allowed by package; only the named writer is.
        var bookingsPackage = ArchitectureRules.COUNTER_WRITERS.evaluate(new ClassFileImporter().importClasses(
                com.agilityhub.core.clubs.bookings.application.fixtures.CounterWriterFixture.class));
        assertThat(bookingsPackage.hasViolation()).isTrue();
        assertThatCode(() -> ArchitectureRules.COUNTER_WRITERS.check(new ClassFileImporter().importClasses(
                com.agilityhub.core.clubs.bookings.application.BookingCounters.class, ClassSessionBookingAccess.class))).doesNotThrowAnyException();
    }

    @Test void E5_T14_theActivityConsumerEnvelopeIsNotADomainEventEither() {
        assertThat(ArchitectureRules.CONSUMER_ENVELOPES.evaluate(new ClassFileImporter().importClasses(SneakyExternalEvent.class)).hasViolation()).isTrue();
        assertThatCode(() -> ArchitectureRules.CONSUMER_ENVELOPES.check(new ClassFileImporter().importClasses(
                com.agilityhub.core.clubs.activities.domain.ActivityExternalEvent.class))).doesNotThrowAnyException();
    }

    @Test void E5_T06_onlyDemoSeedClassesMayMoveTheBookingTime() {
        var result = ArchitectureRules.BOOKING_TIME_OVERRIDE.evaluate(new ClassFileImporter().importClasses(MovesTheBookingTime.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(line -> line.contains("BookingContext.asOf"));
        assertThatCode(() -> ArchitectureRules.BOOKING_TIME_OVERRIDE.check(new ClassFileImporter().importClasses(DemoRuleFixtureSeeder.class,
                DemoRuleFixtureSeeder.Nested.class, RunsAsTheSeedActor.class))).doesNotThrowAnyException();
    }

    @Test void E5_T09_aConsumerEnvelopeThatIsADomainEventIsAViolation() {
        assertThat(ArchitectureRules.CONSUMER_ENVELOPES.evaluate(new ClassFileImporter().importClasses(SneakyForeignEvent.class)).hasViolation()).isTrue();
        assertThatCode(() -> ArchitectureRules.CONSUMER_ENVELOPES.check(new ClassFileImporter().importClasses(
                com.agilityhub.core.clubs.common.domain.ForeignEvent.class, com.agilityhub.core.clubs.bookings.domain.ForeignEvent.class,
                com.agilityhub.core.clubs.training.domain.TrainingForeignEvent.class))).doesNotThrowAnyException();
    }
}

/** A top-level `Demo*` class (and its nested classes) may run as the seed actor. */
final class DemoRuleFixtureSeeder {
    Object run() { return DemoSeedActor.as("account", "MEMBER", () -> "work"); }
    Object asOf(BookingContext context) { return context.asOf(Instant.EPOCH, () -> "work"); }
    static final class Nested {
        Object run() { return DemoSeedActor.as("account", "INSTRUCTOR", () -> "work"); }
    }
}
