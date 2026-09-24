package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.bookings.api.BookingContracts;
import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.clubs.common.api.RiskReviewContracts;
import com.agilityhub.core.clubs.training.api.TrainingContracts;
import com.agilityhub.core.clubs.training.domain.TrainingEvent;
import com.agilityhub.core.platform.application.jobs.JobViews;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import static org.assertj.core.api.Assertions.*;

class E5ResponseContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(JsonInclude.Include.NON_NULL);

    static Class<?> type(String fixtureKey) throws ClassNotFoundException {
        String name = fixtureKey.split("#")[0];
        return switch (name) {
            case "RiskReviewForm" -> RiskReviewContracts.RiskReview.class;
            case "JobRun" -> JobViews.JobRunView.class;
            case "JobSummaries", "JobSwitchResponse", "PlatformJobsOverview" -> Class.forName(JobViews.class.getName() + "$" + name);
            default -> {
                try { yield Class.forName(BookingContracts.class.getName() + "$" + name); }
                catch (ClassNotFoundException training) { yield Class.forName(TrainingContracts.class.getName() + "$" + name); }
            }
        };
    }

    @Test void T_08_47_T_09_24_T_15_29_formsKeepEveryDocumentedFieldDuringRoundTrip() throws Exception {
        int forms = 0;
        for (String group : List.of("bookings", "training", "jobs")) {
            try (var input = getClass().getResourceAsStream("/fixtures/contracts/e5-" + group + "-responses.json")) {
                var fields = mapper.readTree(input).fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    Object value = mapper.treeToValue(entry.getValue(), type(entry.getKey()));
                    JSONAssert.assertEquals(entry.getKey(), entry.getValue().toString(), mapper.writeValueAsString(value), true);
                    forms++;
                }
            }
        }
        assertThat(forms).isEqualTo(22);
    }

    @Test void T_09_24_memberProjectionsNeverExposeTheSeatGuardOrStaffOnlyDataByName() {
        assertThat(Arrays.stream(TrainingContracts.TrainingBooking.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("seatIndex", "idempotencyKey");
        assertThat(Arrays.stream(BookingContracts.Booking.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("packMovementId", "chargeInvoiceLineRef", "version");
        assertThat(Arrays.stream(JobViews.JobRunView.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("holder", "exclusive", "leaseExpired");
    }

    @Test void T_08_47_T_09_24_T_15_29_eventsHaveExactlyTheApprovedPayloadFieldsAndEnvelope() throws Exception {
        var fixtures = mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e5-events.json"));
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ESDEVENIMENTS.md"));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("SeatHeld", "classId,memberId,dogId,expiresAt"); fields.put("SeatHoldReleased", "classId,memberId,dogId,expiresAt");
        fields.put("BookingCreated", "bookingId,classId,memberId,dogId,origin,swapFromBookingId,packMovementId,waitlistEntryId");
        fields.put("BookingCancelled", "bookingId,by,late,minutesBefore,origin,reason");
        fields.put("SeatReleased", "classId,freeSeats,minutesBefore,notifyWaitlist");
        fields.put("WaitlistJoined", "entryId,classId,memberId,dogId"); fields.put("WaitlistLeft", "entryId,classId,memberId,dogId");
        fields.put("WaitlistNotified", "entryIds,classId,confirmBy,mode"); fields.put("WaitlistConsolidated", "entryId,bookingId");
        fields.put("TrainingBooked", "trainingBookingId,slotId,ringId,memberId,dogId,origin");
        fields.put("TrainingCancelled", "trainingBookingId,slotId,ringId,memberId,dogId,origin,by,cancelReason,late");
        fields.put("SchedulerRun", "job,clubId,runId,scheduledFor,trigger,status,startedAt,finishedAt,counters,errorCount");
        fields.put("WeekOpened", "openedWeekKey,isoWeekStart,currentWeekKey,opensAt,notified"); fields.put("TrainingCounterReset", "weekStart");
        fields.put("ClassAtRisk", "classId,dogsCount,reviewAt,newBookingIds,notifyAdmins");
        fields.put("ClassAutoCancelled", "classId,dogsCount,affected,waitlistIds,adminText");
        fields.put("ClassBelowMinimum", "classId,countedDogs,minDogs"); fields.put("ReminderDue", "bookingId,memberId,dogId,startsAt");
        fields.put("WaitlistExpired", "entryId,classId"); fields.put("JobFailed", "job,runId,status,errorCount");
        assertThat(fixtures.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(fields.keySet());
        var kinds = new HashSet<String>();
        for (var kind : BookingEvent.Kind.values()) { kinds.add(kind.name()); }
        for (var kind : TrainingEvent.Kind.values()) { kinds.add(kind.name()); }
        for (var kind : SchedulerEvent.Kind.values()) { kinds.add(kind.name()); }
        assertThat(kinds).containsExactlyInAnyOrderElementsOf(fields.keySet());
        for (var entry : fields.entrySet()) {
            String name = entry.getKey();
            assertThat(catalog).contains("`" + name);
            assertThat(fixtures.path(name).fieldNames()).toIterable().as(name).containsExactlyInAnyOrder(entry.getValue().split(","));
            @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.convertValue(fixtures.path(name), Map.class);
            DomainEvent event = event(name, payload);
            assertThat(event.type()).isEqualTo(name);
            assertThat(event.clubId()).isEqualTo("club-a"); assertThat(event.aggregateId()).isEqualTo("aggregate-a");
            assertThat(event.aggregateType()).isIn("SeatHold", "Booking", "ClassSession", "WaitlistEntry", "TrainingBooking", "JobRun", "Week", "Club");
            assertThat(event.occurredAt()).isNotNull(); assertThat(event.actorAccountId()).isEqualTo("account-a");
            assertThat(event.impersonatedMemberId()).isEqualTo("member-a"); assertThat(event.origin()).isEqualTo(DomainEvent.Origin.BACKOFFICE);
            assertThat(event.payload()).isEqualTo(payload);
            assertThatThrownBy(() -> event.payload().put("extra", true)).isInstanceOf(UnsupportedOperationException.class);
            // The outbox stores the JSON of the envelope and hands consumers the same record back.
            assertThat(mapper.readValue(mapper.writeValueAsString(event), event.getClass())).isEqualTo(event);
        }
    }
    private static DomainEvent event(String name, Map<String, Object> payload) {
        Instant at = Instant.parse("2026-10-05T05:30:00Z");
        for (var kind : BookingEvent.Kind.values()) {
            if (kind.name().equals(name)) { return new BookingEvent(kind, "club-a", "aggregate-a", at, payload, "account-a", "member-a", DomainEvent.Origin.BACKOFFICE); }
        }
        for (var kind : TrainingEvent.Kind.values()) {
            if (kind.name().equals(name)) { return new TrainingEvent(kind, "club-a", "aggregate-a", at, payload, "account-a", "member-a", DomainEvent.Origin.BACKOFFICE); }
        }
        return new SchedulerEvent(SchedulerEvent.Kind.valueOf(name), "club-a", "aggregate-a", at, payload, "account-a", "member-a", DomainEvent.Origin.BACKOFFICE);
    }

    @Test void T_08_47_T_09_24_T_15_29_notificationsMatchTheirCatalogRowsAndVariables() throws Exception {
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_NOTIFICACIONS.md"));
        var fixtures = mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e5-notifications.json"));
        // E5-T10: also the E5-T05 notices; N-16 once per audience (`audience` is the ICU select of its template).
        assertThat(fixtures.fieldNames()).toIterable().containsExactly("N-04", "N-05", "N-06", "N-07", "N-15", "N-16#MEMBER", "N-16#STAFF", "N-17", "N-33",
                "N-36", "N-40", "N-42", "N-46", "N-47", "N-54");
        fixtures.fields().forEachRemaining(entry -> {
            String code = entry.getKey().split("#")[0];
            String row = catalog.lines().filter(line -> line.startsWith("| " + code + " |")).findFirst().orElseThrow();
            assertThat(row).contains("`" + entry.getValue().path("event").asText());
            // N-07 writes «idem»: its variables are the ones of N-06.
            String variables = row.contains("| idem |") ? catalog.lines().filter(line -> line.startsWith("| N-06 |")).findFirst().orElseThrow() : row;
            entry.getValue().path("variables").forEach(variable -> assertThat(variables).as(entry.getKey()).contains(variable.asText()));
            if (!entry.getValue().path("action").isNull()) { assertThat(row).contains(entry.getValue().path("action").asText()); }
        });
    }
}
