package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.followup.domain.*;
import com.agilityhub.core.clubs.followup.persistence.*;
import com.agilityhub.core.clubs.scheduling.domain.CapacityMode;
import com.agilityhub.core.clubs.scheduling.domain.ClassState;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSession;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;

/** E6-T01 step 5/7: the S10 documents, their S10 §3 indexes and `ClassSession.attendanceSummary` (S10 owns it, S06 keeps it). */
class E6PersistenceIT extends AbstractIntegrationTest {
    static final String CLUB = "e6-persistence-a", OTHER = "e6-persistence-b";
    @Autowired MongoTemplate mongo;
    @Autowired AttendanceRepository attendances;
    @Autowired TaskRepository tasks;
    @Autowired FollowupItemRepository items;
    @Autowired FollowupReadMarkRepository marks;

    @BeforeEach void clean() {
        for (String collection : List.of("attendances", "tasks", "followup_items", "followup_read_marks", "class_sessions")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
    }
    private Map<String, Document> indexes(String collection) {
        var result = new LinkedHashMap<String, Document>();
        for (Document index : mongo.getCollection(collection).listIndexes()) { result.put(index.getString("name"), index); }
        return result;
    }
    private static Document keys(String... fields) {
        var keys = new Document();
        for (String field : fields) { keys.append(field, 1); }
        return keys;
    }

    @Test void T_10_21_collectionsDeclareTheS10IndexesAndUniqueGuards() {
        var attendance = indexes("attendances");
        assertThat(attendance.get("attendance_club_booking").get("key")).isEqualTo(keys("clubId", "bookingId"));
        assertThat(attendance.get("attendance_club_booking").getBoolean("unique")).isTrue();
        assertThat(attendance.get("attendance_club_class").get("key")).isEqualTo(keys("clubId", "classSessionId"));
        assertThat(attendance.get("attendance_club_dog_starts").get("key")).isEqualTo(keys("clubId", "dogId", "classStartsAt"));
        assertThat(attendance.get("attendance_club_state_notice_date").get("key")).isEqualTo(keys("clubId", "state", "noShowNotice.queuedAt", "classDate"));
        assertThat(indexes("tasks").get("task_club_dog_deleted_state_created").get("key")).isEqualTo(keys("clubId", "dogId", "deletedAt", "state", "createdAt"));
        var followup = indexes("followup_items");
        assertThat(followup.get("followup_club_activity").get("key")).isEqualTo(keys("clubId", "activityAt"));
        assertThat(followup.get("followup_club_kind_activity").get("key")).isEqualTo(keys("clubId", "kind", "activityAt"));
        var reads = indexes("followup_read_marks").get("followup_read_club_account");
        assertThat(reads.get("key")).isEqualTo(keys("clubId", "accountId"));
        assertThat(reads.getBoolean("unique")).isTrue();
        assertThat(indexes("attachments").get("attachment_club_entity_removed").get("key")).isEqualTo(keys("clubId", "entityType", "entityId", "removedAt"));
    }

    @Test void T_10_08_T_10_22_attendanceTasksAndReadMarksRoundTripWithinTheTenant() {
        Instant now = clock.instant(), starts = Instant.parse("2026-08-03T06:30:00Z");
        try (var scope = TenantContext.open(CLUB)) {
            attendances.insert(new Attendance("e6p-att-a", CLUB, "e6p-booking-a", "e6p-class-a", LocalDate.of(2026, 8, 3), starts, starts.plusSeconds(3600),
                    "dog-a", "member-a", AttendanceState.NOTIFIED, now, new Attendance.Marker("account-a", ActorRole.INSTRUCTOR, "Estel"),
                    new Attendance.Notice(now, false, 1190, true, true, false, BookingState.CANCELLED), new Attendance.NoShowNotice(now, "event-a", null),
                    List.of(new Attendance.Change(AttendanceState.NOTIFIED, now, "account-a")), null, now, now));
            var stored = attendances.findById("e6p-att-a").orElseThrow();
            assertThat(stored.notice().bookingState()).isEqualTo(BookingState.CANCELLED);
            assertThat(stored.markedBy().role()).isEqualTo(ActorRole.INSTRUCTOR);
            assertThat(stored.history()).hasSize(1);
            // `classDate` is a club-local date stored as text, so the P3 claim can compare `classDate < today`.
            assertThat(mongo.getCollection("attendances").find(new Document("_id", "e6p-att-a")).first().get("classDate")).isEqualTo("2026-08-03");
            assertThatThrownBy(() -> attendances.insert(new Attendance("e6p-att-b", CLUB, "e6p-booking-a", "e6p-class-a", LocalDate.of(2026, 8, 3), starts,
                    starts, "dog-a", "member-a", AttendanceState.PRESENT, now, null, null, null, List.of(), null, now, now))).isInstanceOf(DuplicateKeyException.class);
            tasks.insert(new Task("e6p-task-a", CLUB, "dog-a", "member-a", "Practiqueu el balancí", TaskState.PENDING,
                    new Task.Actor("account-a", AuthorRole.INSTRUCTOR, "Estel"), null, null, null, now, now, null, null, 1, null));
            assertThat(tasks.findById("e6p-task-a").orElseThrow().createdBy().displayName()).isEqualTo("Estel");
            items.insert(new FollowupItem("e6p-item-a", CLUB, FollowupKind.TASK, "e6p-task-a", "dog-a", "member-a", "account-a", AuthorRole.INSTRUCTOR,
                    "Estel", "Practiqueu el balancí", now, null, now, false, now));
            marks.insert(new FollowupReadMark("e6p-read-a", CLUB, "account-b", now, List.of("e6p-item-a"), now, now));
            assertThatThrownBy(() -> marks.insert(new FollowupReadMark("e6p-read-b", CLUB, "account-b", now, List.of(), now, now)))
                    .isInstanceOf(DuplicateKeyException.class);
            assertThatThrownBy(() -> tasks.insert(new Task("e6p-task-x", OTHER, "dog-a", "member-a", "x", TaskState.PENDING, null, null, null, null,
                    now, now, null, null, 0, null))).isInstanceOf(ApiException.class);
        }
        try (var scope = TenantContext.open(OTHER)) {
            assertThat(attendances.findById("e6p-att-a")).isEmpty(); assertThat(tasks.findAll()).isEmpty();
            assertThat(items.findAll()).isEmpty(); assertThat(marks.findAll()).isEmpty();
        }
    }

    @Test void T_10_12_classSessionReadsTheEmptySummaryBeforeS10AndKeepsTheOneS10Wrote() {
        Instant now = clock.instant();
        // Compatibility constructor of the finishedAt era: the summary defaults to {version: 0, marked: 0, …}.
        var legacy = new ClassSession("e6p-class-a", CLUB, "e6p-week-a", LocalDate.of(2026, 8, 3), "08:30", "09:30", Instant.parse("2026-08-03T06:30:00Z"),
                Instant.parse("2026-08-03T07:30:00Z"), null, List.of(), List.of(), 5, CapacityMode.AUTO, null, ClassState.ACTIVE, new ClassSession.Counters(4, 1),
                new ClassSession.Risk(false, List.of(), null, null), null, null, null, null, 1L, now, "account-a", now, "account-a", null);
        assertThat(legacy.attendanceSummary()).isEqualTo(ClassSession.AttendanceSummary.EMPTY);
        mongo.insert(legacy);
        // A document written before S10 has no field at all.
        mongo.getCollection("class_sessions").updateOne(new Document("_id", "e6p-class-a"), new Document("$unset", new Document("attendanceSummary", "")));
        assertThat(mongo.findById("e6p-class-a", ClassSession.class).attendanceSummary()).isEqualTo(new ClassSession.AttendanceSummary(0, 0, 0, 0, 0, null, null));
        mongo.getCollection("class_sessions").updateOne(new Document("_id", "e6p-class-a"), new Document("$set", new Document("attendanceSummary",
                new Document("version", 4).append("marked", 3).append("present", 1).append("notified", 1).append("noShow", 1)
                        .append("savedAt", Date.from(now)).append("savedByName", "Estel"))));
        var stored = mongo.findById("e6p-class-a", ClassSession.class);
        // A summary written before `notifiedAfterEnd` existed reads it as 0 (round 2 compatibility constructor).
        assertThat(stored.attendanceSummary()).isEqualTo(new ClassSession.AttendanceSummary(4, 3, 1, 1, 1, now, "Estel"));
        assertThat(stored.attendanceSummary().notifiedAfterEnd()).isZero();
        mongo.getCollection("class_sessions").updateOne(new Document("_id", "e6p-class-a"),
                new Document("$set", new Document("attendanceSummary.notifiedAfterEnd", 1)));
        assertThat(mongo.findById("e6p-class-a", ClassSession.class).attendanceSummary())
                .isEqualTo(new ClassSession.AttendanceSummary(4, 3, 1, 1, 1, 1, now, "Estel"));
    }
}
