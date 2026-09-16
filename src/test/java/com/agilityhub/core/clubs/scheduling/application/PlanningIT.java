package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PlanningIT extends AbstractIntegrationTest {
    static final String CLUB = "planning-a", OTHER = "planning-b", HOST = "planning.example.test";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired TemplateQuery templateQuery;
    @Autowired ClassSessionRepository sessions;
    @Autowired UsageCounter usage;
    @Autowired CatalogRepository<Level> levels;
    @Autowired InstructorUsageCounter instructorUsage;
    @BeforeEach void prepare() {
        clock.setInstant(Instant.parse("2026-08-24T04:00:00Z"));
        for (String collection : List.of("parameters", "levels", "rings", "instructors", "week_templates", "weeks", "class_sessions", "members", "dogs", "idempotency_records", "audit_entries", "domain_events")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        mongo.remove(Query.query(Criteria.where("_id").in(CLUB, OTHER)), Club.class);
        for (String id : List.of(CLUB, OTHER)) {
            var club = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, id.equals(CLUB) ? HOST : "other.planning.example.test"));
            club.set("locales", mapper.valueToTree(List.of("ca", "es", "en"))); clubs.save(mapper.convertValue(club, Club.class));
            configs.invalidate(id); templateQuery.invalidate(id);
        }
        hosts.invalidate(); int order = 0;
        for (String name : List.of("Cadells", "A", "B", "C", "D", "E", "F", "G")) {
            try (var tenant = TenantContext.open(CLUB)) { levels.insert(new Level("plan-level-" + name, CLUB, name.toUpperCase(), new LocalizedText(Map.of("ca", name, "es", name, "en", name), "ca"),
                    order++, "#112233", name.equals("C") ? 4 : 5, false, true, 0, clock.instant(), clock.instant(), "admin", "admin")); }
        }
        mongo.insert(new Ring("plan-ring", CLUB, "Example", "EX", "#112233", false, null, 0, true, 0, clock.instant(), clock.instant(), "admin", "admin"));
        for (String id : List.of("plan-instructor", "plan-instructor-2")) {
            mongo.insert(new Instructor(id, CLUB, id + "-member", id, "#112233", true, 0, clock.instant(), clock.instant(), "admin", "admin"));
        }
    }
    MockHttpServletRequestBuilder call(String method, String path, Object body) throws Exception {
        var request = request(HttpMethod.valueOf(method), "/api/v1" + path).header("Host", HOST)
                .with(jwt().jwt(j -> j.subject("planning-admin").claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN"));
        if (body != null) { request.contentType("application/json").content(mapper.writeValueAsString(body)); }
        return request;
    }
    JsonNode ok(String method, String path, Object body) throws Exception {
        var response = mvc.perform(call(method, path, body)).andExpect(status().is2xxSuccessful()).andReturn().getResponse();
        return response.getContentAsByteArray().length == 0 ? mapper.nullNode() : mapper.readTree(response.getContentAsByteArray());
    }
    void error(String method, String path, Object body, ErrorCode code) throws Exception {
        mvc.perform(call(method, path, body)).andExpect(status().is(code.httpStatus())).andExpect(jsonPath("$.code").value(code.name())).andExpect(jsonPath("$.traceId").isNotEmpty());
    }
    String template(String name, String kind) throws Exception { return ok("POST", "/week-templates", Map.of("name", name, "kind", kind)).path("id").asText(); }
    JsonNode band(String id) throws Exception { return ok("POST", "/week-templates/" + id + "/bands", Map.of("startTime", "18:00", "endTime", "19:00")); }
    Map<String, Object> classBody(String band) { return new LinkedHashMap<>(Map.of("bandId", band, "dayOfWeek", "MONDAY", "ringId", "plan-ring", "instructorIds", List.of("plan-instructor"), "levelIds", List.of("plan-level-B", "plan-level-C"))); }
    JsonNode patchClass(String template, String classId, long version, String key, Object value) throws Exception {
        var patch = new LinkedHashMap<String, Object>(); patch.put("version", version); patch.put(key, value);
        return ok("PATCH", "/week-templates/" + template + "/classes/" + classId, patch);
    }
    void parameter(String key, Object value, String type) {
        mongo.remove(Query.query(Criteria.where("_id").is(CLUB + ":" + key)), Parameter.class);
        mongo.insert(new Parameter(CLUB + ":" + key, CLUB, key, value, type, "club", null, List.of(), 0L, clock.instant())); configs.invalidate(CLUB);
    }
    @Test @AuditCovers({AuditAction.TEMPLATE_CLASS_DELETED, AuditAction.TEMPLATE_BAND_DELETED})
    void T_06_01_T_06_09_templateCrudCopyNullResetsAuditEventsAndUsage() throws Exception {
        String id = template("Week A", "WEEKDAYS"); String path = "/week-templates/" + id;
        error("POST", "/week-templates", Map.of("name", "week a", "kind", "WEEKDAYS"), ErrorCode.DUPLICATE_NAME);
        template("Week A", "SATURDAY");
        var withBand = band(id); String band = withBand.at("/bands/0/id").asText();
        var view = ok("POST", path + "/classes", classBody(band)); String classId = view.at("/classes/0/id").asText();
        assertThat(view.at("/classes/0/displayDescription").asText()).isEqualTo("B+C"); assertThat(view.at("/classes/0/capacity").asInt()).isEqualTo(4);
        error("DELETE", path + "/bands/" + band, null, ErrorCode.BAND_NOT_EMPTY);
        error("PATCH", path + "/classes/" + classId, Map.of("version", 0, "capacity", 7), ErrorCode.STALE_VERSION);
        view = patchClass(id, classId, view.path("version").asLong(), "capacity", 7);
        view = patchClass(id, classId, view.path("version").asLong(), "levelIds", List.of("plan-level-B", "plan-level-C", "plan-level-D"));
        assertThat(view.at("/classes/0/capacity").asInt()).isEqualTo(7);
        view = patchClass(id, classId, view.path("version").asLong(), "capacity", null);
        assertThat(view.at("/classes/0/capacity").asInt()).isEqualTo(4); assertThat(view.at("/classes/0/capacityMode").asText()).isEqualTo("AUTO");
        view = patchClass(id, classId, view.path("version").asLong(), "description", "Manual");
        view = patchClass(id, classId, view.path("version").asLong(), "ringId", null);
        assertThat(view.at("/classes/0/displayDescription").asText()).isEqualTo("Manual"); assertThat(view.at("/classes/0/ringId").isNull() || view.at("/classes/0/ringId").isMissingNode()).isTrue();
        view = patchClass(id, classId, view.path("version").asLong(), "description", null);
        assertThat(view.at("/classes/0/displayDescription").asText()).isEqualTo("B+C+D");
        view = ok("PATCH", path + "/bands/" + band, Map.of("version", view.path("version").asLong(), "startTime", "18:10", "endTime", "19:10"));
        var copy = ok("POST", "/week-templates", Map.of("name", "Copy", "kind", "WEEKDAYS", "copyFromId", id));
        assertThat(copy.at("/classes/0/id").asText()).isNotEqualTo(classId); assertThat(copy.at("/bands/0/id").asText()).isNotEqualTo(band);
        assertThat(copy.at("/classes/0/bandId").asText()).isEqualTo(copy.at("/bands/0/id").asText());
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(usage.usage(com.agilityhub.core.clubs.catalogs.domain.CatalogKind.LEVEL, "plan-level-B").get("templateClasses")).isEqualTo(2L);
            assertThat(instructorUsage.usage("plan-instructor").templateClasses()).isEqualTo(2L);
        }
        ok("DELETE", path + "/classes/" + classId, null); ok("DELETE", path + "/bands/" + band, null);
        var audit = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").in("TEMPLATE_CLASS_DELETED", "TEMPLATE_BAND_DELETED")), Document.class, "audit_entries");
        assertThat(audit).hasSize(2); assertThat(audit).allSatisfy(a -> assertThat(a.getList("changes", Document.class)).isNotEmpty());
        assertThat(ok("GET", path, null).path("classes")).isEmpty();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("WeekTemplateChanged")), "domain_events")).isGreaterThan(10);
        error("PATCH", path, Map.of("version", view.path("version").asLong(), "kind", "SATURDAY"), ErrorCode.VALIDATION_ERROR);
    }
    @Test void T_06_09_T_06_19_validationVariantsAndLocalizedLiveCatalog() throws Exception {
        String id = template("Variants", "WEEKDAYS"), path = "/week-templates/" + id; var view = band(id); String band = view.at("/bands/0/id").asText();
        var body = classBody("foreign"); error("POST", path + "/classes", body, ErrorCode.VALIDATION_ERROR);
        body = classBody(band); body.put("instructorIds", List.of("plan-instructor", "plan-instructor-2")); error("POST", path + "/classes", body, ErrorCode.TOO_MANY_INSTRUCTORS);
        parameter("classes.maxInstructorsPerClass", 2, "int"); view = ok("POST", path + "/classes", body);
        body.put("levelIds", List.of()); error("POST", path + "/classes", body, ErrorCode.LEVEL_REQUIRED);
        parameter("levels.enabled", false, "bool"); error("POST", path + "/classes", body, ErrorCode.DESCRIPTION_REQUIRED);
        body.put("description", "Manual"); view = ok("POST", path + "/classes", body); assertThat(view.at("/classes/1/capacity").asInt()).isEqualTo(5);
        assertThat(view.path("canGenerate").asBoolean()).isFalse();
        error("GET", "/coverage?templateId=" + id, null, ErrorCode.LEVELS_DISABLED);
        parameter("levels.enabled", true, "bool");
        String classId = view.at("/classes/0/id").asText(); view = patchClass(id, classId, view.path("version").asLong(), "levelIds", List.of("plan-level-D", "plan-level-E", "plan-level-F", "plan-level-G"));
        mvc.perform(call("GET", path, null).header("Accept-Language", "es")).andExpect(jsonPath("$.classes[0].displayDescription").value("D y sup."));
        mvc.perform(call("GET", path, null).header("Accept-Language", "en")).andExpect(jsonPath("$.classes[0].displayDescription").value("D and up"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("plan-level-G")), new Update().set("active", false), "levels");
        var updated = ok("GET", path, null); assertThat(updated.path("inconsistencies").toString()).contains("LEVEL_INACTIVE");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("plan-ring")), new Update().set("active", false), "rings");
        assertThat(ok("GET", path, null).path("inconsistencies").toString()).contains("RING_INACTIVE");
    }
    @Test void T_06_01_T_06_31_templateFiltersAndInactiveGenerationPreservePendingWeek() throws Exception {
        String active = template("Active weekdays", "WEEKDAYS");
        String inactive = template("Archived weekdays", "WEEKDAYS");
        String saturday = template("Saturday", "SATURDAY");
        var archived = ok("GET", "/week-templates/" + inactive, null);
        ok("PATCH", "/week-templates/" + inactive, Map.of("version", archived.path("version").asLong(), "active", false));
        assertThat(ok("GET", "/week-templates", null).path("items")).hasSize(3);
        var weekdays = ok("GET", "/week-templates?kind=WEEKDAYS", null).path("items");
        assertThat(weekdays).hasSize(2);
        assertThat(weekdays.findValuesAsText("id")).containsExactly(active, inactive);
        var archivedOnly = ok("GET", "/week-templates?active=false", null).path("items");
        assertThat(archivedOnly).hasSize(1);
        assertThat(archivedOnly.get(0).path("id").asText()).isEqualTo(inactive);
        var saturdayOnly = ok("GET", "/week-templates?kind=SATURDAY&active=true", null).path("items");
        assertThat(saturdayOnly).hasSize(1);
        assertThat(saturdayOnly.get(0).path("id").asText()).isEqualTo(saturday);
        String pending = week("2026-08-24");
        generation(pending, inactive, null, UUID.randomUUID().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(ok("GET", "/weeks/" + pending, null).path("state").asText()).isEqualTo("PENDING");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("WeekGenerated")), "domain_events")).isZero();
    }
    void sourceTemplates() {
        var bands = new ArrayList<WeekTemplate.TimeBand>();
        for (int i = 0; i < 9; i++) { bands.add(new WeekTemplate.TimeBand("band-" + i, "%02d:00".formatted(7 + i), "%02d:00".formatted(8 + i))); }
        var classes = new ArrayList<WeekTemplate.TemplateClass>(); int[] counts = {9, 9, 5, 9, 8};
        for (int day = 1; day <= 5; day++) { for (int i = 0; i < counts[day - 1]; i++) { classes.add(tc("tc-" + day + "-" + i, "band-" + i, DayOfWeek.of(day))); } }
        mongo.insert(new WeekTemplate("plan-weekdays", CLUB, "Fixture weekdays", TemplateKind.WEEKDAYS, null, true, bands, classes, 0L, clock.instant(), "admin", clock.instant(), "admin"));
        mongo.insert(new WeekTemplate("plan-saturday", CLUB, "Fixture Saturday", TemplateKind.SATURDAY, null, true, bands,
                java.util.stream.IntStream.range(0, 6).mapToObj(i -> tc("sat-" + i, "band-" + i, DayOfWeek.SATURDAY)).toList(), 0L, clock.instant(), "admin", clock.instant(), "admin"));
    }
    WeekTemplate.TemplateClass tc(String id, String band, DayOfWeek day) { return new WeekTemplate.TemplateClass(id, band, day, List.of("plan-instructor"), "plan-ring", List.of("plan-level-B"), 5, CapacityMode.AUTO, null, null); }
    String week(String date) throws Exception { return ok("POST", "/weeks", Map.of("startDate", date)).path("id").asText(); }
    ResultActions generation(String id, String weekday, String saturday, String key) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("weekdayTemplateId", weekday); body.put("saturdayTemplateId", saturday);
        return mvc.perform(call("POST", "/weeks/" + id + "/generation", body).header("Idempotency-Key", key));
    }
    @Test void T_06_11_T_06_31_generationSkipsHolidaysPastDaysPreservesLooseClassesAndChangesNoSources() throws Exception {
        sourceTemplates(); parameter("club.holidays", List.of(Map.of("date", "2026-08-26", "label", "Example holiday")), "list");
        String id = week("2026-08-24");
        mvc.perform(call("POST", "/weeks", Map.of("startDate", "2026-08-24"))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        generation(id, "plan-weekdays", "plan-saturday", UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.classCount").value(41))
                .andExpect(jsonPath("$.skipped[0].date").value("2026-08-26")).andExpect(jsonPath("$.skipped[0].reason").value("HOLIDAY")).andExpect(jsonPath("$.skipped[0].count").value(5));
        assertThat(ok("GET", "/weeks/" + id, null).path("state").asText()).isEqualTo("GENERATED");
        assertThat(mongo.findById(id, Document.class, "weeks").get("startDate")).isEqualTo("2026-08-24");
        try (var tenant = TenantContext.open(CLUB)) { assertThat(sessions.forWeek(id)).hasSize(41).allSatisfy(c -> { assertThat(c.state()).isEqualTo(ClassState.DRAFT); assertThat(c.counters().booked()).isZero(); assertThat(c.risk().exempt()).isFalse(); assertThat(c.origin()).isNotNull(); }); }
        generation(id, "plan-weekdays", null, UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEEK_ALREADY_GENERATED"));
        var list = ok("GET", "/weeks?filter=state:eq:GENERATED&sort=startDate,desc", null); assertThat(list.at("/items/0/startDate").asText()).isEqualTo("2026-08-24"); assertThat(list.at("/items/0/classCounts/draft").asInt()).isEqualTo(41); assertThat(list.at("/items/0/weekdayTemplateName").asText()).isEqualTo("Fixture weekdays");
        var sparse = ok("GET", "/weeks?fields=state&filter=startDate:gte:2026-08-24", null);
        assertThat(sparse.at("/items/0").fieldNames()).toIterable().containsExactlyInAnyOrder("id", "state");
        var candidates = ok("GET", "/weeks/generation-candidates", null); assertThat(candidates.at("/items/0/startDate").asText()).isEqualTo("2026-08-31"); assertThat(candidates.at("/items/0/proposed").asBoolean()).isTrue();
        String next = week("2026-08-31"); generation(next, "plan-weekdays", null, UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.classCount").value(40));
        String past = week("2026-08-17"); generation(past, "plan-weekdays", null, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WEEK_IN_PAST"));
        clock.setInstant(Instant.parse("2026-09-09T10:00:00Z")); String current = week("2026-09-07");
        generation(current, "plan-weekdays", null, UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.classCount").value(22)).andExpect(jsonPath("$.skipped[0].reason").value("PAST"));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("type").is("WeekGenerated")), "domain_events")).isEqualTo(3);
    }
    @Test void T_06_10_T_06_22_inconsistencyKindsValidatedWeekConcurrencyAndReplay() throws Exception {
        sourceTemplates(); parameter("club.holidays", List.of(Map.of("date", "2026-08-26", "label", "Example holiday")), "list"); String id = week("2026-08-24");
        generation(id, "plan-saturday", null, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TEMPLATE_KIND_MISMATCH"));
        generation(id, "plan-weekdays", "plan-weekdays", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TEMPLATE_KIND_MISMATCH"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("plan-ring")), new Update().set("active", false), "rings");
        generation(id, "plan-weekdays", null, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.details.inconsistencies[0].type").value("RING_INACTIVE"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("plan-ring")), new Update().set("active", true), "rings");
        var latch = new CountDownLatch(1); var keys = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = keys.stream().map(key -> pool.submit(() -> { latch.await(); return generation(id, "plan-weekdays", "plan-saturday", key).andReturn().getResponse(); })).toList();
            latch.countDown(); var responses = List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
            assertThat(responses).extracting(r -> r.getStatus()).containsExactlyInAnyOrder(200, 409);
            for (var response : responses) { if (response.getStatus() == 409) { assertThat(mapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("WEEK_ALREADY_GENERATED"); } }
            for (int i = 0; i < 2; i++) { if (responses.get(i).getStatus() == 200) { assertThat(generation(id, "plan-weekdays", "plan-saturday", keys.get(i)).andReturn().getResponse().getContentAsString()).isEqualTo(responses.get(i).getContentAsString()); } }
        }
        try (var tenant = TenantContext.open(CLUB)) { assertThat(sessions.forWeek(id)).hasSize(41); }
        String validated = week("2026-08-31"); mongo.updateFirst(Query.query(Criteria.where("_id").is(validated)), new Update().set("state", "VALIDATED"), "weeks");
        generation(validated, "plan-weekdays", null, UUID.randomUUID().toString()).andExpect(status().isConflict());
    }
    @Test void T_06_18_coverageCombinesTemplatesUsesCensusAndExcludesCancelledClasses() throws Exception {
        sourceTemplates();
        mongo.insert(new Document("_id", "plan-member").append("clubId", CLUB).append("status", "ACTIVE"), "members");
        mongo.insert(new Document("_id", "plan-dog").append("clubId", CLUB).append("status", "ACTIVE").append("memberId", "plan-member").append("levelId", "plan-level-B"), "dogs");
        var coverage = ok("GET", "/coverage?templateId=plan-weekdays&saturdayTemplateId=plan-saturday", null);
        JsonNode b = java.util.stream.StreamSupport.stream(coverage.path("levels").spliterator(), false).filter(l -> l.path("levelId").asText().equals("plan-level-B")).findFirst().orElseThrow();
        assertThat(b.path("maxSeats").asInt()).isEqualTo(230); assertThat(b.path("dogsTotal").asInt()).isEqualTo(1); assertThat(b.path("dogsActive").asInt()).isZero(); assertThat(b.path("status").asText()).isEqualTo("NO_DOGS");
        String id = week("2026-08-24"); generation(id, "plan-weekdays", null, UUID.randomUUID().toString()).andExpect(status().isOk());
        mongo.updateFirst(Query.query(Criteria.where("weekId").is(id)), new Update().set("state", "CANCELLED"), "class_sessions");
        coverage = ok("GET", "/coverage?weekId=" + id, null);
        b = java.util.stream.StreamSupport.stream(coverage.path("levels").spliterator(), false).filter(l -> l.path("levelId").asText().equals("plan-level-B")).findFirst().orElseThrow();
        assertThat(b.path("maxSeats").asInt()).isEqualTo(195); assertThat(b.path("booked").asInt(-1)).isZero();
    }
    @Test void T_06_01_bandAndTemplateUpdatesRespectVersionKindNamesAndGeneratedSnapshots() throws Exception {
        String id = template("Editable", "WEEKDAYS"); String path = "/week-templates/" + id;
        var view = band(id); String band = view.at("/bands/0/id").asText();
        error("POST", path + "/bands", Map.of("startTime", "18:30", "endTime", "19:30"), ErrorCode.BAND_OVERLAP);
        error("POST", path + "/bands", Map.of("startTime", "25:00", "endTime", "26:00"), ErrorCode.INVALID_TIME_RANGE);
        error("POST", path + "/bands", Map.of("startTime", "18:05", "endTime", "19:00"), ErrorCode.INVALID_SLOT_GRANULARITY);
        error("POST", path + "/bands", Map.of("startTime", "06:00", "endTime", "07:00"), ErrorCode.OUTSIDE_OPENING_HOURS);
        view = ok("PATCH", path, Map.of("version", view.path("version").asLong(), "notes", "Planning notes", "name", "Renamed"));
        assertThat(view.path("notes").asText()).isEqualTo("Planning notes");
        var patch = new LinkedHashMap<String, Object>(); patch.put("version", view.path("version").asLong()); patch.put("notes", null); patch.put("active", false);
        view = ok("PATCH", path, patch); assertThat(view.path("notes").isNull() || view.path("notes").isMissingNode()).isTrue();
        assertThat(ok("GET", "/week-templates?kind=WEEKDAYS&active=false", null).path("items")).hasSize(1);
        String week = week("2026-08-24");
        generation(week, id, null, UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        view = ok("PATCH", path, Map.of("version", view.path("version").asLong(), "active", true));
        view = ok("POST", path + "/classes", classBody(band));
        generation(week, id, null, UUID.randomUUID().toString()).andExpect(status().isOk());
        ok("PATCH", path + "/bands/" + band, Map.of("version", view.path("version").asLong(), "endTime", "19:30"));
        try (var tenant = TenantContext.open(CLUB)) { assertThat(sessions.forWeek(week).getFirst().endTime()).isEqualTo("19:00"); }
        error("POST", "/week-templates", Map.of("name", "Wrong kind", "kind", "SATURDAY", "copyFromId", id), ErrorCode.TEMPLATE_KIND_MISMATCH);
        error("POST", "/week-templates", Map.of("name", "   ", "kind", "WEEKDAYS"), ErrorCode.VALIDATION_ERROR);
        error("PATCH", path, Map.of("name", " ", "version", ok("GET", path, null).path("version").asLong()), ErrorCode.VALIDATION_ERROR);
        error("GET", "/coverage?templateId=" + template("Saturday", "SATURDAY"), null, ErrorCode.TEMPLATE_KIND_MISMATCH);
        error("GET", "/weeks?filter=unknown:eq:value", null, ErrorCode.INVALID_FILTER);
    }
    @Test void T_06_11_failedGenerationRollsBackWeekClassesEventAndIdempotencyClaim() throws Exception {
        sourceTemplates(); String id = week("2026-08-24"); String key = UUID.randomUUID().toString();
        long events = mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "domain_events");
        mongo.executeCommand(new Document("collMod", "class_sessions").append("validator", new Document("state", new Document("$ne", "DRAFT"))));
        try { generation(id, "plan-weekdays", null, key).andExpect(status().isInternalServerError()); }
        finally { mongo.executeCommand(new Document("collMod", "class_sessions").append("validator", new Document())); }
        assertThat(ok("GET", "/weeks/" + id, null).path("state").asText()).isEqualTo("PENDING");
        try (var tenant = TenantContext.open(CLUB)) { assertThat(sessions.forWeek(id)).isEmpty(); }
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "domain_events")).isEqualTo(events);
        generation(id, "plan-weekdays", null, key).andExpect(status().isOk()).andExpect(jsonPath("$.classCount").value(40));
    }
    @Test void T_06_31_candidatesIncludePendingWeeksExcludeValidatedAndKeepLooseClasses() throws Exception {
        sourceTemplates();
        var initial = ok("GET", "/weeks/generation-candidates", null);
        assertThat(initial.path("items")).hasSize(13); assertThat(initial.at("/items/0/startDate").asText()).isEqualTo("2026-08-24");
        assertThat(initial.at("/items/0/proposed").asBoolean()).isTrue();
        String previous = week("2026-08-17");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(previous)), new Update().set("state", "GENERATED").set("generatedAt", clock.instant()), "weeks");
        String pending = week("2026-08-31"), validated = week("2026-09-07");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(validated)), new Update().set("state", "VALIDATED"), "weeks");
        var candidates = ok("GET", "/weeks/generation-candidates", null);
        assertThat(candidates.path("items").toString()).contains(pending).doesNotContain(validated);
        assertThat(candidates.at("/items/0/startDate").asText()).isEqualTo("2026-08-24"); assertThat(candidates.at("/items/0/proposed").asBoolean()).isTrue();
        LocalDate date = LocalDate.of(2026, 8, 31); Instant at = date.atTime(20, 0).atZone(ZoneId.of("Europe/Madrid")).toInstant();
        mongo.insert(new ClassSession("planning-loose", CLUB, pending, date, "20:00", "21:00", at, at.plusSeconds(3600), null,
                List.of("plan-level-B"), List.of("plan-instructor"), 3, CapacityMode.MANUAL, "Loose class", ClassState.DRAFT,
                new ClassSession.Counters(0, 0), new ClassSession.Risk(false, List.of(), null, null), null, null, null, null,
                0L, clock.instant(), "admin", clock.instant(), "admin"));
        generation(pending, "plan-weekdays", null, UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.classCount").value(40));
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(sessions.forWeek(pending)).hasSize(41);
            assertThat(sessions.findById("planning-loose").orElseThrow().description()).isEqualTo("Loose class");
        }
    }
    @Test void T_06_33_generationRecomputesLocalHoursAcrossDstAndArgentina() throws Exception {
        sourceTemplates();
        for (String zone : List.of("Europe/Madrid", "America/Argentina/Buenos_Aires")) {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(clubs.findById(CLUB).orElseThrow()); tree.put("timeZone", zone); clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(CLUB);
            for (String date : List.of("2026-10-19", "2026-10-26")) {
                mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "weeks"); mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "class_sessions");
                String id = week(date); generation(id, "plan-weekdays", "plan-saturday", UUID.randomUUID().toString()).andExpect(status().isOk());
                try (var tenant = TenantContext.open(CLUB)) {
                    assertThat(sessions.forWeek(id)).allSatisfy(c -> assertThat(c.startsAt()).isEqualTo(LocalDateTime.of(c.date(), LocalTime.parse(c.startTime())).atZone(ZoneId.of(zone)).toInstant()));
                }
            }
        }
    }
}
