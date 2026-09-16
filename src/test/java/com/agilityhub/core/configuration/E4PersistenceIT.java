package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ClubScheduleQuery;
import com.agilityhub.core.clubs.catalogs.application.*;
import com.agilityhub.core.clubs.catalogs.domain.CatalogKind;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;

class E4PersistenceIT extends AbstractIntegrationTest {
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired WeekTemplateRepository templates;
    @Autowired WeekRepository weeks;
    @Autowired ClassSessionRepository classes;
    @Autowired RingBlockRepository blocks;
    @Autowired ActivityRepository activities;
    @Autowired ActivityRegistrationRepository registrations;
    @Autowired ClubScheduleQuery schedule;
    @Autowired UsageCounter usage;
    @Autowired InstructorUsageCounter instructorUsage;
    static final String A = "e4-persistence-a", B = "e4-persistence-b";
    @BeforeEach void clear() {
        for (String name : List.of("week_templates", "weeks", "class_sessions", "ring_blocks", "activities", "activity_registrations")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(A, B)), name);
        }
    }
    private <T> T entity(Class<T> type, String id, String clubId, Map<String, Object> fields) {
        var values = new LinkedHashMap<String, Object>(fields); values.put("id", id); values.put("clubId", clubId);
        values.put("version", 0); values.put("createdAt", clock.instant()); values.put("updatedAt", clock.instant());
        values.put("createdByAccountId", "account-a"); values.put("updatedByAccountId", "account-a");
        return mapper.convertValue(values, type);
    }
    @Test void T_06_20_T_07_17_allSixRepositoriesEnforceTenantBoundariesAndPersistAuditMetadata() {
        isolate(templates, WeekTemplate.class, Map.of("name", "Example", "kind", "WEEKDAYS", "timeBands", List.of(), "classes", List.of()));
        isolate(weeks, Week.class, Map.of("startDate", "2026-09-14", "state", "PENDING"));
        isolate(classes, ClassSession.class, Map.of("state", "ACTIVE"));
        isolate(blocks, RingBlock.class, Map.of("state", "ACTIVE"));
        isolate(activities, Activity.class, Map.of("slug", "example-event", "state", "DRAFT"));
        isolate(registrations, ActivityRegistration.class, Map.of("activityId", "activity-a", "memberId", "member-a", "state", "ACTIVE"));
    }
    private <T extends TenantEntity> void isolate(TenantRepository<T> repository, Class<T> type, Map<String, Object> fields) {
        var a = entity(type, "e4-" + type.getSimpleName(), A, fields);
        assertThatThrownBy(() -> repository.findById(a.id())).hasMessage("NO_MEMBERSHIP");
        try (var scope = TenantContext.open(A)) {
            repository.insert(a); assertThat(repository.findById(a.id())).contains(a);
            var stored = mongo.getCollection(mongo.getCollectionName(type)).find(new Document("_id", a.id())).first();
            assertThat(stored).containsKeys("clubId", "version", "createdAt", "createdByAccountId", "updatedAt", "updatedByAccountId");
        }
        try (var scope = TenantContext.open(B)) {
            assertThat(repository.findById(a.id())).isEmpty(); assertThat(repository.findAll()).isEmpty();
            assertThat(repository.deleteById(a.id())).isFalse();
            assertThatThrownBy(() -> repository.insert(a)).hasMessage("TENANT_MISMATCH");
            assertThatThrownBy(() -> repository.replace(a)).hasMessage("TENANT_MISMATCH");
            assertThatThrownBy(() -> repository.replace(entity(type, a.id(), B, fields))).isInstanceOf(DuplicateKeyException.class);
        }
        try (var scope = TenantContext.open(A)) { assertThat(repository.findById(a.id())).contains(a); }
    }
    @Test void T_06_20_T_07_17_realMongoIndexesEnforceCaseInsensitiveNamesWeeksSlugsAndOnlyLiveRegistrations() {
        try (var scope = TenantContext.open(A)) {
            templates.insert(entity(WeekTemplate.class, "template-one", A, Map.of("kind", "WEEKDAYS", "name", "Example")));
            assertThatThrownBy(() -> templates.insert(entity(WeekTemplate.class, "template-two", A, Map.of("kind", "WEEKDAYS", "name", "EXAMPLE")))).isInstanceOf(DuplicateKeyException.class);
            templates.insert(entity(WeekTemplate.class, "template-saturday", A, Map.of("kind", "SATURDAY", "name", "EXAMPLE")));
            weeks.insert(entity(Week.class, "week-one", A, Map.of("startDate", "2026-09-14")));
            assertThatThrownBy(() -> weeks.insert(entity(Week.class, "week-two", A, Map.of("startDate", "2026-09-14")))).isInstanceOf(DuplicateKeyException.class);
            activities.insert(entity(Activity.class, "activity-one", A, Map.of("slug", "example-event")));
            assertThatThrownBy(() -> activities.insert(entity(Activity.class, "activity-two", A, Map.of("slug", "example-event")))).isInstanceOf(DuplicateKeyException.class);
            registrations.insert(registration("cancelled-1", A, "CANCELLED")); registrations.insert(registration("cancelled-2", A, "CANCELLED"));
            registrations.insert(registration("active", A, "ACTIVE"));
            assertThatThrownBy(() -> registrations.insert(registration("waiting", A, "WAITLISTED"))).isInstanceOf(DuplicateKeyException.class);
            registrations.deleteById("active"); registrations.insert(registration("waiting", A, "WAITLISTED"));
            assertThatThrownBy(() -> registrations.insert(registration("active-2", A, "ACTIVE"))).isInstanceOf(DuplicateKeyException.class);
        }
        try (var scope = TenantContext.open(B)) {
            templates.insert(entity(WeekTemplate.class, "template-other", B, Map.of("kind", "WEEKDAYS", "name", "EXAMPLE")));
            weeks.insert(entity(Week.class, "week-other", B, Map.of("startDate", "2026-09-14")));
            activities.insert(entity(Activity.class, "activity-other", B, Map.of("slug", "example-event")));
            registrations.insert(registration("registration-other", B, "ACTIVE"));
        }
        Map<String, List<String>> expected = Map.of(
                "week_templates", List.of("clubId,kind,name"), "weeks", List.of("clubId,startDate"),
                "class_sessions", List.of("clubId,date,state", "clubId,startsAt", "clubId,weekId", "clubId,ringId,startsAt", "clubId,state,endsAt"),
                "ring_blocks", List.of("clubId,ringId,from,to", "clubId,from"),
                "activities", List.of("clubId,slug", "clubId,state,startsAt", "clubId,date"),
                "activity_registrations", List.of("clubId,activityId,memberId", "clubId,memberId,activityStartsAt,state", "clubId,activityId,state,position"));
        expected.forEach((name, keys) -> {
            var indexes = mongo.getCollection(name).listIndexes().into(new ArrayList<>());
            assertThat(indexes.stream().map(index -> String.join(",", index.get("key", Document.class).keySet())))
                    .containsAll(keys);
            if (name.equals("activity_registrations")) {
                var live = indexes.stream().filter(i -> i.containsKey("partialFilterExpression")).findFirst().orElseThrow();
                assertThat(live.getBoolean("unique")).isTrue();
                assertThat(live.get("partialFilterExpression", Document.class).toJson()).contains("ACTIVE", "WAITLISTED").doesNotContain("CANCELLED");
            }
        });
    }
    private ActivityRegistration registration(String id, String club, String state) {
        return entity(ActivityRegistration.class, id, club, Map.of("activityId", "activity-a", "memberId", "member-a", "state", state));
    }
    @Test void T_06_20_storageUsesTimeBandsRiskOriginAndCountsEmbeddedClassesWithFinalStates() {
        try (var scope = TenantContext.open(A)) {
            assertThat(schedule.hasClasses()).isFalse();
            var templateClass = Map.of("id", "tc-a", "ringId", "ring-a", "levelIds", List.of("level-a"), "instructorIds", List.of("instructor-a"));
            templates.insert(entity(WeekTemplate.class, "template-model", A, Map.of("kind", "WEEKDAYS", "name", "Example", "timeBands", List.of(Map.of("id", "band-a", "startTime", "18:00", "endTime", "19:00")), "classes", List.of(templateClass, templateClass))));
            classes.insert(entity(ClassSession.class, "class-model", A, Map.of("state", "ACTIVE", "ringId", "ring-a", "levelIds", List.of("level-a"), "instructorIds", List.of("instructor-a"), "startsAt", clock.instant().plusSeconds(3600),
                    "risk", Map.of("exempt", false, "notifiedBookingIds", List.of("booking-a"), "adminNotifiedAt", clock.instant(), "lowAlertSentAt", clock.instant()),
                    "origin", Map.of("templateId", "template-model", "templateClassId", "tc-a"), "counters", Map.of("booked", 1, "waiting", 2))));
            classes.insert(entity(ClassSession.class, "class-cancelled", A, Map.of("state", "CANCELLED", "ringId", "ring-a", "instructorIds", List.of("instructor-a"), "startsAt", clock.instant().plusSeconds(3600))));
            blocks.insert(entity(RingBlock.class, "block-active", A, Map.of("ringId", "ring-a", "state", "ACTIVE")));
            blocks.insert(entity(RingBlock.class, "block-cancelled", A, Map.of("ringId", "ring-a", "state", "CANCELLED")));
            assertThat(schedule.hasClasses()).isTrue();
            assertThat(usage.usage(CatalogKind.LEVEL, "level-a")).containsEntry("templateClasses", 2L).containsEntry("futureClassSessions", 1L);
            assertThat(usage.usage(CatalogKind.RING, "ring-a")).containsEntry("templateClasses", 2L).containsEntry("futureClassSessions", 1L).containsEntry("ringBlocks", 1L);
            assertThat(instructorUsage.usage("instructor-a").templateClasses()).isEqualTo(2);
            assertThat(instructorUsage.hasReferences("instructor-a")).isTrue();
            var stored = mongo.getCollection("week_templates").find(new Document("_id", "template-model")).first();
            assertThat(stored).containsKeys("timeBands", "classes").doesNotContainKeys("bands", "inconsistencies");
            var session = mongo.getCollection("class_sessions").find(new Document("_id", "class-model")).first();
            assertThat(session).containsKeys("risk", "origin", "counters").doesNotContainKeys("riskExempt", "templateClassId", "displayDescription");
            assertThat(session.get("risk", Document.class)).containsKeys("exempt", "notifiedBookingIds", "adminNotifiedAt", "lowAlertSentAt");
            assertThat(session.get("counters", Document.class)).containsEntry("waiting", 2).doesNotContainKey("waitlisted");
        }
        try (var scope = TenantContext.open(B)) {
            assertThat(schedule.hasClasses()).isFalse();
            assertThat(usage.usage(CatalogKind.RING, "ring-a").values()).allMatch(count -> count == 0L);
            assertThat(instructorUsage.hasReferences("instructor-a")).isFalse();
        }
    }
}
