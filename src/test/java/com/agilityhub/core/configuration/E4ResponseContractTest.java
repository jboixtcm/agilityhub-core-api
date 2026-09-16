package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.scheduling.api.SchedulingContracts;
import com.agilityhub.core.clubs.activities.api.ActivityContracts;
import com.agilityhub.core.clubs.scheduling.domain.SchedulingEvent;
import com.agilityhub.core.clubs.activities.domain.ActivityEvent;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import static org.assertj.core.api.Assertions.*;

class E4ResponseContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(JsonInclude.Include.NON_NULL);
    @Test void T_06_20_T_07_17_formsKeepEveryDocumentedFieldDuringRoundTrip() throws Exception {
        for (var pair : Map.of("scheduling", SchedulingContracts.class, "activities", ActivityContracts.class).entrySet()) {
            try (var input = getClass().getResourceAsStream("/fixtures/contracts/e4-" + pair.getKey() + "-responses.json")) {
                var fields = mapper.readTree(input).fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    var type = Class.forName(pair.getValue().getName() + "$" + entry.getKey());
                    Object value = mapper.treeToValue(entry.getValue(), type);
                    JSONAssert.assertEquals(entry.getKey(), entry.getValue().toString(), mapper.writeValueAsString(value), true);
                }
            }
        }
    }
    @Test void T_07_17_publicProjectionHasNoPathToMemberOrRegistrationPersonalData() {
        assertPublicGraph(ActivityContracts.PublicActivity.class, new HashSet<>());
        assertThat(Arrays.stream(SchedulingContracts.RingBlockMemberView.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("note", "createdByName");
        assertThat(Arrays.stream(ActivityContracts.MemberActivityDetail.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("internalNotes", "ringBlockIds", "registeredBy", "counters");
    }
    private void assertPublicGraph(Class<?> type, Set<Class<?>> visited) {
        if (!type.isRecord() || !visited.add(type)) { return; }
        for (var field : type.getRecordComponents()) {
            assertThat(field.getName()).doesNotStartWith("member").doesNotStartWith("phone")
                    .isNotIn("registrations", "registeredBy", "internalNotes", "createdByName", "fullName", "emails");
            assertPublicGraph(field.getType(), visited);
            if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType generic) {
                for (var nested : generic.getActualTypeArguments()) { if (nested instanceof Class<?> child) { assertPublicGraph(child, visited); } }
            }
        }
    }
    @Test void T_06_20_T_07_17_eventsHaveExactlyTheApprovedPayloadFieldsAndEnvelope() throws Exception {
        var fixtures = mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e4-events.json"));
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ESDEVENIMENTS.md"));
        Map<String, String> fields = Map.ofEntries(
                Map.entry("WeekTemplateChanged", "templateId,diff,inconsistencies"), Map.entry("WeekGenerated", "weekId,classCount,templateId"),
                Map.entry("WeekValidated", "weekId,classIds"), Map.entry("ClassSessionCreated", "classId"),
                Map.entry("ClassSessionUpdated", "classId,diff,bookedCount"), Map.entry("ClassCancelledByClub", "classId,reason,adminText,affected,waitlistIds"),
                Map.entry("ClassRiskExemptionChanged", "classId,exempt"), Map.entry("RingBlockCreated", "blockId,ringId,from,to,reason,activityId"),
                Map.entry("RingBlockUpdated", "blockId,diff"), Map.entry("RingBlockCancelled", "blockId"),
                Map.entry("ActivityPublished", "activityId,levelIds,notifyEmail"), Map.entry("ActivityUpdated", "activityId,diff,state,registrantCount"),
                Map.entry("ActivityCancelled", "activityId,reason,adminText,affected"), Map.entry("ActivityFinished", "activityId"),
                Map.entry("ActivityRegistrationChanged", "registrationId,activityId,memberId,state,origin,cancelReason,promoted"));
        assertThat(fixtures.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(fields.keySet());
        for (var entry : fields.entrySet()) {
            String name = entry.getKey();
            assertThat(catalog).contains("`" + name);
            assertThat(fixtures.path(name).fieldNames()).toIterable().containsExactlyInAnyOrder(entry.getValue().split(","));
            @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.convertValue(fixtures.path(name), Map.class);
            DomainEvent event = name.startsWith("Activity")
                    ? new ActivityEvent(ActivityEvent.Kind.valueOf(name), "club-a", "aggregate-a", Instant.parse("2026-09-14T16:00:00Z"), payload, "account-a", "member-a", DomainEvent.Origin.BACKOFFICE)
                    : new SchedulingEvent(SchedulingEvent.Kind.valueOf(name), "club-a", "aggregate-a", Instant.parse("2026-09-14T16:00:00Z"), payload, "account-a", "member-a", DomainEvent.Origin.BACKOFFICE);
            assertThat(event.type()).isEqualTo(name);
            assertThat(event.clubId()).isEqualTo("club-a"); assertThat(event.aggregateId()).isEqualTo("aggregate-a");
            assertThat(event.aggregateType()).isIn("WeekTemplate", "Week", "ClassSession", "RingBlock", "Activity", "ActivityRegistration");
            assertThat(event.occurredAt()).isNotNull(); assertThat(event.actorAccountId()).isEqualTo("account-a");
            assertThat(event.impersonatedMemberId()).isEqualTo("member-a"); assertThat(event.origin()).isEqualTo(DomainEvent.Origin.BACKOFFICE);
            assertThat(event.payload()).isEqualTo(payload);
            assertThatThrownBy(() -> event.payload().put("extra", true)).isInstanceOf(UnsupportedOperationException.class);
        }
    }
    @Test void T_06_20_T_07_17_notificationsMatchTheirCatalogRowsAndVariables() throws Exception {
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_NOTIFICACIONS.md"));
        var fixtures = mapper.readTree(getClass().getResourceAsStream("/fixtures/contracts/e4-notifications.json"));
        assertThat(fixtures.fieldNames()).toIterable().containsExactly("N-08a", "N-08b", "N-32a", "N-32b", "N-32c", "N-32d");
        fixtures.fields().forEachRemaining(entry -> {
            String row = catalog.lines().filter(line -> line.startsWith("| " + entry.getKey() + " |")).findFirst().orElseThrow();
            assertThat(row).contains("`" + entry.getValue().path("event").asText());
            entry.getValue().path("variables").forEach(variable -> assertThat(row).contains(variable.asText()));
        });
    }
}
