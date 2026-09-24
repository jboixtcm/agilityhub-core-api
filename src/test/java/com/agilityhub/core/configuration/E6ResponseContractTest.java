package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.bookings.api.InstructorContracts;
import com.agilityhub.core.clubs.bookings.domain.AttendanceEvent;
import com.agilityhub.core.clubs.followup.api.AttachmentsController;
import com.agilityhub.core.clubs.followup.api.FollowupContracts;
import com.agilityhub.core.clubs.followup.domain.FollowupEvent;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import java.lang.reflect.RecordComponent;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import static org.assertj.core.api.Assertions.*;

/** E6-T01: the S10 §6 forms round-trip every documented field (T-10-21), the §7 events and the §8 notifications match their catalogs. */
class E6ResponseContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(JsonInclude.Include.NON_NULL);

    static Class<?> type(String fixtureKey) throws ClassNotFoundException {
        String name = fixtureKey.split("#")[0];
        if (name.equals("Attachment")) { return AttachmentsController.AttachmentResponse.class; }
        try { return Class.forName(InstructorContracts.class.getName() + "$" + name); }
        catch (ClassNotFoundException followup) { return Class.forName(FollowupContracts.class.getName() + "$" + name); }
    }
    private JsonNode fixtures(String group) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/contracts/e6-" + group + ".json")) { return mapper.readTree(input); }
    }

    @Test void T_10_21_formsKeepEveryDocumentedFieldDuringRoundTrip() throws Exception {
        int forms = 0;
        for (String group : List.of("instructor-responses", "followup-responses")) {
            var fields = fixtures(group).fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                Object value = mapper.treeToValue(entry.getValue(), type(entry.getKey()));
                JSONAssert.assertEquals(entry.getKey(), entry.getValue().toString(), mapper.writeValueAsString(value), true);
                forms++;
            }
        }
        assertThat(forms).isEqualTo(29);
    }

    @Test void T_10_21_theFiveS10FormsCarryTheirExampleFieldsAndModuleOffShapesDropTheirBlocks() throws Exception {
        var instructor = fixtures("instructor-responses");
        assertThat(instructor.path("InstructorDay").fieldNames()).toIterable()
                .containsExactly("date", "timeZone", "selectedInstructorId", "instructors", "days", "classes", "ringBlocks");
        assertThat(instructor.at("/AttendanceSheet/rows/0").fieldNames()).toIterable().contains("final", "pendingTasksCount", "dogPhotoUrl");
        assertThat(instructor.at("/AttendanceSheet/rows/2/notice").fieldNames()).toIterable()
                .containsExactly("atLocal", "at", "late", "minutesBefore", "seatReleased", "waitlistNotified", "afterClassEnd", "bookingState");
        assertThat(instructor.at("/InstructorCard").fieldNames()).toIterable()
                .containsExactly("dog", "member", "level", "metrics", "lastClasses", "instructorNote", "tasks", "observations");
        assertThat(instructor.at("/InstructorCard/metrics").fieldNames()).toIterable().containsExactly("windowDays", "attendancePct", "present", "noShow",
                "notified", "cancelledLate", "classesCounted", "trainingsCount", "trainingsPerWeek");
        assertThat(instructor.at("/MemberHistory").fieldNames()).toIterable().containsExactly("monthsVisible", "from", "showDog", "dogs", "types", "items");
        // S10 §9: TASKS off → no note/tasks/observations/pendingTasksCount; FREE_TRAINING off → no trainings; WAITLIST off → no waiting/waitlist.
        assertThat(instructor.path("InstructorCard#noModules").has("instructorNote")).isFalse();
        assertThat(instructor.path("InstructorCard#noModules").has("tasks")).isFalse();
        assertThat(instructor.path("InstructorCard#noModules").has("observations")).isFalse();
        assertThat(instructor.at("/InstructorCard#noModules/metrics").has("trainingsCount")).isFalse();
        assertThat(instructor.path("AttendanceSheet#noModules").has("waitlist")).isFalse();
        assertThat(instructor.at("/AttendanceSheet#noModules/classSession").has("waiting")).isFalse();
        assertThat(instructor.at("/AttendanceSheet#noModules/rows/0").has("pendingTasksCount")).isFalse();
        assertThat(instructor.at("/InstructorDay#noWaitlist/classes/0").has("waiting")).isFalse();
        var followup = fixtures("followup-responses");
        assertThat(followup.path("Task").fieldNames()).toIterable()
                .containsExactly("id", "dogId", "text", "state", "createdAt", "createdBy", "doneAt", "doneBy", "attachments", "version");
        assertThat(followup.at("/FollowupPage/items/0").fieldNames()).toIterable().containsExactly("id", "kind", "taskId", "dogId", "dogName", "levelCode",
                "memberId", "memberName", "authorName", "authorRole", "textExcerpt", "createdAt", "activityAt", "unread");
    }

    @Test void T_10_14_T_10_17_privateObservationsNeverReachTheMemberForms() {
        for (Class<?> member : List.of(InstructorContracts.MemberHistory.class, InstructorContracts.HistoryItem.class, InstructorContracts.HistoryDog.class)) {
            assertThat(Arrays.stream(member.getRecordComponents()).map(RecordComponent::getName)).as(member.getSimpleName())
                    .doesNotContain("observations", "remarks", "notes");
        }
        assertThat(Arrays.stream(InstructorContracts.AttendanceRow.class.getRecordComponents()).map(RecordComponent::getName)).doesNotContain("remarks", "observations");
    }

    @Test void T_10_21_eventsHaveExactlyTheS10PayloadFieldsAndEnvelope() throws Exception {
        var fixtures = fixtures("events");
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ESDEVENIMENTS.md"));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("AttendanceMarked", "bookingId,classSessionId,dogId,memberId,state,previousState,by,late,afterClassEnd");
        fields.put("NoShowNoticeDue", "attendanceIds,bookingIds");
        fields.put("TaskCreated", "taskId,dogId,memberId,by"); fields.put("TaskUpdated", "taskId,dogId,by"); fields.put("TaskDeleted", "taskId,dogId,by");
        fields.put("TaskCompleted", "taskId,dogId,memberId,by"); fields.put("TaskReopened", "taskId,dogId,by");
        fields.put("AttachmentRemoved", "attachmentId,entityType,entityId");
        assertThat(fixtures.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(fields.keySet());
        var kinds = new HashSet<String>();
        for (var kind : AttendanceEvent.Kind.values()) { kinds.add(kind.name()); }
        for (var kind : FollowupEvent.Kind.values()) { kinds.add(kind.name()); }
        assertThat(kinds).containsExactlyInAnyOrderElementsOf(fields.keySet());
        for (var entry : fields.entrySet()) {
            String name = entry.getKey();
            assertThat(catalog).contains("`" + name);
            assertThat(fixtures.path(name).fieldNames()).toIterable().as(name).containsExactlyInAnyOrder(entry.getValue().split(","));
            @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.convertValue(fixtures.path(name), Map.class);
            DomainEvent event = event(name, payload);
            assertThat(event.type()).isEqualTo(name);
            assertThat(event.aggregateType()).isEqualTo(name.startsWith("Attendance") || name.startsWith("NoShow") ? "Attendance" : name.startsWith("Task") ? "Task" : "Attachment");
            assertThat(event.clubId()).isEqualTo("club-a"); assertThat(event.aggregateId()).isEqualTo("aggregate-a");
            assertThat(event.actorAccountId()).isEqualTo("account-a"); assertThat(event.impersonatedMemberId()).isNull();
            assertThat(event.origin()).isEqualTo(DomainEvent.Origin.INSTRUCTOR); assertThat(event.occurredAt()).isNotNull();
            assertThat(event.payload()).isEqualTo(payload);
            assertThatThrownBy(() -> event.payload().put("extra", true)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(mapper.readValue(mapper.writeValueAsString(event), event.getClass())).isEqualTo(event);
        }
        // Annex A lists the S10 extensions: AttendanceMarked{classSessionId, memberId, previousState, late, afterClassEnd}, TaskReopened, AttachmentRemoved.
        String annex = catalog.lines().filter(line -> line.startsWith("| `AttendanceMarked{")).findFirst().orElseThrow();
        assertThat(annex).contains("classSessionId", "memberId", "previousState", "late", "afterClassEnd", "`TaskReopened`", "`AttachmentRemoved`");
    }
    private static DomainEvent event(String name, Map<String, Object> payload) {
        Instant at = Instant.parse("2026-08-03T06:41:10Z");
        for (var kind : AttendanceEvent.Kind.values()) {
            if (kind.name().equals(name)) { return new AttendanceEvent(kind, "club-a", "aggregate-a", at, payload, "account-a", null, DomainEvent.Origin.INSTRUCTOR); }
        }
        return new FollowupEvent(FollowupEvent.Kind.valueOf(name), "club-a", "aggregate-a", at, payload, "account-a", null, DomainEvent.Origin.INSTRUCTOR);
    }

    @Test void T_10_21_notificationsCarryExactlyTheCatalogVariables() throws Exception {
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_NOTIFICACIONS.md"));
        var fixtures = fixtures("notifications");
        assertThat(fixtures.fieldNames()).toIterable().containsExactly("N-05", "N-15", "N-19", "N-20", "N-21", "N-22");
        fixtures.fields().forEachRemaining(entry -> {
            String row = catalog.lines().filter(line -> line.startsWith("| " + entry.getKey() + " |")).findFirst().orElseThrow();
            String[] cells = row.split("\\|");
            assertThat(cells[3]).contains("`" + entry.getValue().path("event").asText());
            var variables = Arrays.stream(cells[6].split(",")).map(v -> v.replaceAll("\\(.*\\)", "").strip()).toList();
            var fixture = new ArrayList<String>(); entry.getValue().path("variables").forEach(v -> fixture.add(v.asText()));
            assertThat(fixture).as(entry.getKey()).containsExactlyElementsOf(variables);
            if (!entry.getValue().path("action").isNull()) { assertThat(cells[7]).contains(entry.getValue().path("action").asText()); }
            else { assertThat(cells[7].strip()).isEqualTo("—"); }
        });
    }
}
