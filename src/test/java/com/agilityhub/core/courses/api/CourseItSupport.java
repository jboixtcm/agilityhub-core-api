package com.agilityhub.core.courses.api;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** Two fictional clubs with rings and levels for the E9-T02 behaviour tests (S16 R-16-01…04, 12, 14, 16, 17). */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
abstract class CourseItSupport extends AbstractIntegrationTest {
    static final String CLUB = "e9t2-club-a", OTHER = "e9t2-club-b", HOST = "e9t2-a.example.test", OTHER_HOST = "e9t2-b.example.test";
    static final List<String> DATA = List.of("courses", "placements", "ring_setups", "obstacle_inventories", "calibration_logs", "venues", "rings", "levels",
            "domain_events", "audit_entries", "attachment_uploads", "idempotency_records");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;

    @BeforeEach void prepareClubs() {
        for (String collection : DATA) {
            mongo.remove(Query.query(new Criteria().orOperator(Criteria.where("clubId").in(CLUB, OTHER), Criteria.where("_id").regex("^e9t2-"),
                    Criteria.where("clubId").is(null).and("createdByAccountId").regex("^e9t2-"))), collection);
        }
        for (String club : List.of(CLUB, OTHER)) {
            mongo.remove(Query.query(Criteria.where("_id").is(club)), Club.class);
            var json = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(club, club.equals(CLUB) ? HOST : OTHER_HOST));
            json.set("modules", mapper.valueToTree(List.of(Module.values())));
            clubs.save(mapper.convertValue(json, Club.class)); configs.invalidate(club);
            String suffix = club.equals(CLUB) ? "a" : "b";
            for (int i = 1; i <= 2; i++) {
                mongo.save(new Document("_id", "e9t2-ring-" + suffix + i).append("clubId", club).append("name", "Ring " + suffix.toUpperCase() + i)
                        .append("shortName", "R" + suffix + i).append("color", "#123456").append("allowsFreeTraining", false).append("order", i * 10)
                        .append("active", true).append("version", 3L), "rings");
            }
            for (String code : List.of("B", "A")) {
                var level = new Document();
                mongo.getConverter().write(new com.agilityhub.core.clubs.catalogs.persistence.Level("e9t2-level-" + suffix + code, club, code,
                        new com.agilityhub.core.shared.domain.LocalizedText(Map.of("ca", "Nivell " + code), "ca"), code.equals("A") ? 10 : 20, "#123456", 6,
                        false, true, true, 0L, clock.instant(), clock.instant(), "e9t2-actor", "e9t2-actor"), level);
                level.put("nameKeys", List.of("ca:nivell " + code.toLowerCase(Locale.ROOT)));
                mongo.insert(level, "levels");
            }
        }
        hosts.invalidate();
    }
    @AfterEach void removeParameters() {
        mongo.remove(Query.query(Criteria.where("_id").regex("^e9t2-param-")), "parameters");
        configs.invalidate(CLUB); configs.invalidate(OTHER);
    }
    void parameter(String key, Object value, String type) {
        mongo.insert(new Parameter("e9t2-param-" + key, CLUB, key, value, type, "club", null, List.of(), 0L, clock.instant())); configs.invalidate(CLUB);
    }

    /** A request of {@code role} (subject `e9t2-{role}`) in {@code club}; AGILITYHUB_ADMIN gets no club. */
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String club, String role) { return as(request, club, role, "e9t2-" + role); }
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String club, String role, String subject) {
        if (role.equals("AGILITYHUB_ADMIN")) {
            return request.with(jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN")));
        }
        return request.header("Host", club.equals(CLUB) ? HOST : OTHER_HOST)
                .with(jwt().jwt(j -> j.subject(subject).claim("clubId", club).claim("memberId", "e9t2-member")).authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType("application/json").content(body instanceof String text ? text : mapper.writeValueAsString(body));
    }
    MvcResult perform(MockHttpServletRequestBuilder request, int status) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " "
                + result.getResponse().getContentAsString()).isEqualTo(status);
        return result;
    }
    JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? mapper.nullNode() : mapper.readTree(content);
    }
    JsonNode ok(MockHttpServletRequestBuilder request, int status) throws Exception { return body(perform(request, status)); }
    /** An error answer: its status and code (CATALEG_ERRORS rule 0), with the details the api sends. */
    JsonNode error(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        var body = ok(request, status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("traceId").asText()).isNotEmpty();
        return body.path("details");
    }
    List<Document> events(String type) {
        return mongo.find(Query.query(Criteria.where("type").is(type)).with(org.springframework.data.domain.Sort.by("occurredAt", "_id")), Document.class, "domain_events");
    }
    long count(String collection, Query query) { return mongo.count(query, collection); }

    /** A fictional valid `CourseData` (course-core v1) with {@code obstacles} obstacles; the last is `Unknown` when {@code unknown}. */
    ObjectNode model(int obstacles, boolean unknown) throws Exception {
        ObjectNode model;
        try (var input = getClass().getResourceAsStream("/fixtures/courses/course-data-valid.json")) { model = (ObjectNode) mapper.readTree(input); }
        var template = model.path("obstacles").get(0).deepCopy();
        var list = model.putArray("obstacles");
        for (int i = 0; i < obstacles; i++) {
            var obstacle = ((ObjectNode) template.deepCopy()).put("id", "obstacle-" + i).put("sourceId", "source-" + i);
            if (unknown && i == obstacles - 1) { obstacle.put("obstacleType", "Unknown"); }
            list.add(obstacle);
        }
        return model;
    }
    ObjectNode course(String name, ObjectNode model) {
        var body = mapper.createObjectNode();
        body.putObject("name").put("ca", name);
        body.put("discipline", "AGILITY").put("source", "SMARTER").put("visibility", "CLUB").put("schemaVersion", 1);
        body.set("normalizedJson", model); body.putArray("levelIds"); body.putArray("tags");
        return body;
    }
}
