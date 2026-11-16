package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CompetitionImportsIT extends AbstractIntegrationTest {
    static final String CLUB = "stats-a", OTHER = "stats-b", BASE = "/api/v1/competition-imports";
    @Autowired CompetitionPrivacy privacy;
    @org.springframework.test.context.bean.override.mockito.MockitoBean FlowSourceClient flowClient;
    @org.springframework.test.context.bean.override.mockito.MockitoBean FcagSourceClient fcagClient;
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @BeforeEach void seed() {
        TenantContext.clear();
        for (String collection : List.of("competition_imports", "competition_previews", "clubs", "members", "dogs", "parameters", "audit_entries", "idempotency_records")) {
            mongo.remove(new Query(), collection);
        }
        for (String id : List.of(CLUB, OTHER)) {
            var club = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(id, id + ".example.test"));
            club.withArray("modules").add("STATS"); clubs.save(mapper.convertValue(club, Club.class)); configs.invalidate(id);
            var member = new Member(); member.id = id + "-member"; member.clubId = id; member.accountId = id + "-account"; member.status = "ACTIVE";
            mongo.insert(member);
            var dog = new Dog(); dog.id = id + "-dog"; dog.clubId = id; dog.memberId = member.id; dog.name = "Núvol"; dog.status = "ACTIVE";
            dog.licenses = List.of(Map.of("organisation", "FCAG", "number", "0123"), Map.of("organisation", "RSCE", "number", "0123")); mongo.insert(dog);
        }
        hosts.invalidate();
    }
    ResultActions call(MockHttpServletRequestBuilder request, String club, String role) throws Exception {
        return mvc.perform(request.header("Host", club + ".example.test").header("Idempotency-Key", UUID.randomUUID())
                .with(jwt().jwt(j -> j.subject(club + "-account").claim("clubId", club).claim("name", "Example"))
                        .authorities(() -> "ROLE_" + role)));
    }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object input) throws Exception {
        return request.contentType("application/json").content(mapper.writeValueAsString(input));
    }
    JsonNode json(ResultActions response) throws Exception {
        return mapper.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode preview(String html) throws Exception {
        return json(call(body(post(BASE + "/preview"), Map.of("source", "FCAG", "sourceUrl", CompetitionParsersTest.FCAG_URL, "content", html)), CLUB, "ADMIN"));
    }
    Map<String, Object> assignments(JsonNode preview, String dog) {
        return Map.of("assignments", Map.of(preview.at("/rows/0/entry/key").asText(), dog));
    }
    @Test void T_STATS_08_previewConfirmReplayAndOfficialCorrection() throws Exception {
        var preview = preview(CompetitionParsersTest.FCAG);
        assertThat(preview.at("/rows/0/suggestedDogId").asText()).isEqualTo(CLUB + "-dog");
        String path = BASE + "/" + preview.path("id").asText() + "/confirm";
        var input = assignments(preview, CLUB + "-dog");
        var receipt = json(call(body(post(path), input), CLUB, "ADMIN"));
        assertThat(json(call(body(post(path), input), CLUB, "ADMIN"))).isEqualTo(receipt);
        var fresh = preview(CompetitionParsersTest.FCAG.replace("<td>140</td>", "<td>145</td>"));
        json(call(body(post(BASE + "/" + fresh.path("id").asText() + "/confirm"), assignments(fresh, CLUB + "-dog")), CLUB, "ADMIN"));
        var stats = json(call(get("/api/v1/me/dogs/" + CLUB + "-dog/competition-statistics").param("season", "2026-2027"), CLUB, "MEMBER"));
        assertThat(stats.path("standings")).hasSize(1);
        assertThat(stats.at("/standings/0/result/points").asInt()).isEqualTo(145);
        assertThat(mongo.getCollection("competition_imports").countDocuments()).isEqualTo(1);
    }
    @Test void T_STATS_09_rolesTenantsOwnershipModuleAndInvalidAssignments() throws Exception {
        var preview = preview(CompetitionParsersTest.FCAG); String path = BASE + "/" + preview.path("id").asText() + "/confirm";
        for (String role : List.of("MEMBER", "INSTRUCTOR")) {
            call(get(BASE), CLUB, role).andExpect(status().isForbidden());
            call(body(post(path), assignments(preview, CLUB + "-dog")), CLUB, role).andExpect(status().isForbidden());
        }
        call(body(post(path), assignments(preview, OTHER + "-dog")), CLUB, "ADMIN").andExpect(status().isBadRequest());
        call(body(post(path), assignments(preview, OTHER + "-dog")), OTHER, "ADMIN").andExpect(status().isNotFound());
        call(get("/api/v1/me/dogs/" + OTHER + "-dog/competition-statistics"), CLUB, "MEMBER").andExpect(status().isNotFound());
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB + "-dog")), new Update().set("memberId", "another-owner"), Dog.class);
        call(get("/api/v1/me/dogs/" + CLUB + "-dog/competition-statistics"), CLUB, "MEMBER").andExpect(status().isForbidden());
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().pull("modules", "STATS"), Club.class); configs.invalidate(CLUB);
        call(get(BASE), CLUB, "ADMIN").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
    }
    @Test void T_STATS_11_exportAndErasureAreTenantScoped() throws Exception {
        var preview = preview(CompetitionParsersTest.FCAG);
        json(call(body(post(BASE + "/" + preview.path("id").asText() + "/confirm"), assignments(preview, CLUB + "-dog")), CLUB, "ADMIN"));
        var other = json(call(body(post(BASE + "/preview"), Map.of("source", "FCAG", "sourceUrl", CompetitionParsersTest.FCAG_URL,
                "content", CompetitionParsersTest.FCAG)), OTHER, "ADMIN"));
        var member = new com.agilityhub.core.platform.application.privacy.ErasureMember(CLUB + "-member", CLUB + "-account", 7,
                "ACTIVE", null, null, null, null, List.of(CLUB + "-dog"));
        try (var ignored = TenantContext.open(CLUB)) {
            var own = new com.agilityhub.core.platform.application.privacy.MemberDataPackage(); privacy.contribute(member, own);
            assertThat(mapper.valueToTree(own.sections()).path("competitionResults")).hasSize(1);
            var empty = new com.agilityhub.core.platform.application.privacy.MemberDataPackage();
            privacy.contribute(new com.agilityhub.core.platform.application.privacy.ErasureMember("unrelated", null, null, "ACTIVE",
                    null, null, null, null, List.of("not-owned")), empty);
            assertThat(mapper.valueToTree(empty.sections()).path("competitionResults")).isEmpty();
            var subject = new com.agilityhub.core.platform.application.privacy.ErasureSubject("request", CLUB, List.of(member), Set.of(), Map.of(), null, clock.instant());
            assertThat(privacy.erase(subject).documents()).containsEntry("competition_imports", 1L).containsEntry("competition_previews", 1L).containsEntry("idempotency_records", 1L);
            assertThat(privacy.erase(subject).documents()).isEmpty();
            privacy.contribute(member, own);
            assertThat(mapper.valueToTree(own.sections()).path("competitionResults")).isEmpty();
        }
        assertThat(mongo.getCollection("competition_previews").countDocuments(new org.bson.Document("_id", other.path("id").asText()))).isEqualTo(1);
        assertThat(mongo.getCollection("idempotency_records").countDocuments(new org.bson.Document("clubId", OTHER))).isEqualTo(1);
        // The successful confirmation receipt is not personal source data; preserve its retry protection.
        assertThat(mongo.getCollection("idempotency_records").countDocuments(new org.bson.Document("clubId", CLUB))).isEqualTo(1);
    }
    @Test void T_STATS_10_staleAndExpiredPreviewsCannotOverwriteResults() throws Exception {
        var first = preview(CompetitionParsersTest.FCAG); var stale = preview(CompetitionParsersTest.FCAG);
        json(call(body(post(BASE + "/" + first.path("id").asText() + "/confirm"), assignments(first, CLUB + "-dog")), CLUB, "ADMIN"));
        call(body(post(BASE + "/" + stale.path("id").asText() + "/confirm"), assignments(stale, CLUB + "-dog")), CLUB, "ADMIN")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
        var expired = preview(CompetitionParsersTest.FCAG); clock.advance(java.time.Duration.ofHours(25));
        call(body(post(BASE + "/" + expired.path("id").asText() + "/confirm"), assignments(expired, CLUB + "-dog")), CLUB, "ADMIN").andExpect(status().isBadRequest());
    }
    @Test void T_STATS_50_numberedRunsRemainSeparateAfterImportAndReadback() throws Exception {
        for (int index = 0; index < 3; index++) {
            String run = List.of("AG1", "AG2", "JP1").get(index);
            String url = FlowPrintResultsParserTest.URL.replace("789abc", "789ab" + index);
            String html = FlowPrintResultsParserTest.html(index < 2 ? "1PA" : "1PV").replace(" / JP", " / " + run).replace("0.00", "-");
            var preview = json(call(body(post(BASE + "/preview"), Map.of("source", "FLOW_AGILITY", "sourceUrl", url, "content", html)), CLUB, "ADMIN"));
            json(call(body(post(BASE + "/" + preview.path("id").asText() + "/confirm"), assignments(preview, CLUB + "-dog")), CLUB, "ADMIN"));
        }
        var stats = json(call(get("/api/v1/dogs/" + CLUB + "-dog/competition-statistics").param("year", "2026"), CLUB, "ADMIN"));
        assertThat(stats.path("results")).hasSize(3);
        assertThat(stats.path("scoredTrials").asInt()).isEqualTo(1);
        assertThat(stats.at("/summaries/0/runs").asInt()).isEqualTo(2);
        assertThat(stats.at("/summaries/0/pa").asInt()).isEqualTo(2);
        assertThat(stats.at("/summaries/0/clearRuns").asInt()).isEqualTo(2);
        assertThat(stats.at("/summaries/1/runs").asInt()).isEqualTo(1);
        assertThat(stats.at("/summaries/1/pv").asInt()).isEqualTo(1);
    }
    @Test void T_STATS_25_authenticatedFlowAndSavedCsvShareReviewedStatistics() throws Exception {
        org.mockito.Mockito.when(flowClient.fetch(FlowPrintResultsParserTest.URL)).thenReturn(FlowPrintResultsParserTest.html("PA"));
        var preview = json(call(body(post(BASE + "/preview"), Map.of("source", "FLOW_AGILITY", "sourceUrl", FlowPrintResultsParserTest.URL)), CLUB, "ADMIN"));
        json(call(body(post(BASE + "/" + preview.path("id").asText() + "/confirm"), assignments(preview, CLUB + "-dog")), CLUB, "ADMIN"));
        var stats = json(call(get("/api/v1/dogs/" + CLUB + "-dog/competition-statistics").param("year", "2026"), CLUB, "INSTRUCTOR"));
        assertThat(stats.path("scoredTrials").asInt()).isEqualTo(1);
        assertThat(stats.at("/summaries/1/pa").asInt()).isEqualTo(1);
        var csv = FlowResultsParser.HEADER + "\n" + CompetitionParsersTest.flowRow("a", "AGILITY", "PV");
        var csvPreview = json(call(body(post(BASE + "/preview"), Map.of("source", "FLOW_AGILITY", "sourceUrl", CompetitionParsersTest.FLOW_URL, "content", csv)), CLUB, "ADMIN"));
        assertThat(csvPreview.at("/rows/0/entry/point").asText()).isEqualTo("PV");
        var htmlPreview = json(call(body(post(BASE + "/preview"), Map.of("source", "FLOW_AGILITY", "sourceUrl", FlowPrintResultsParserTest.URL, "content", "\uFEFF" + FlowPrintResultsParserTest.html("-"))), CLUB, "ADMIN"));
        assertThat(htmlPreview.at("/rows/0/entry/point").asText()).isEqualTo("NONE");
        assertThat(json(call(get(BASE), CLUB, "ADMIN"))).hasSize(1);
        for (String year : List.of("1999", "2101")) call(get("/api/v1/dogs/" + CLUB + "-dog/competition-statistics").param("year", year), CLUB, "ADMIN").andExpect(status().isBadRequest());
        for (String season : List.of("bad", "2026-2028")) call(get("/api/v1/dogs/" + CLUB + "-dog/competition-statistics").param("season", season), CLUB, "ADMIN").andExpect(status().isBadRequest());
    }
    @Test void T_STATS_26_ambiguousLicencesAndInvalidSelectionsNeverPublish() throws Exception {
        org.mockito.Mockito.when(fcagClient.fetch(CompetitionParsersTest.FCAG_URL)).thenReturn(CompetitionParsersTest.FCAG);
        var copy = mongo.findById(CLUB + "-dog", Dog.class); copy.id = CLUB + "-second"; mongo.insert(copy);
        var preview = json(call(body(post(BASE + "/preview"), Map.of("source", "FCAG", "sourceUrl", CompetitionParsersTest.FCAG_URL)), CLUB, "ADMIN"));
        assertThat(preview.at("/rows/0/match").asText()).isEqualTo("AMBIGUOUS_LICENSE");
        var unmatched = preview(CompetitionParsersTest.FCAG.replace("0123", "9999"));
        assertThat(unmatched.at("/rows/0/match").asText()).isEqualTo("UNMATCHED");
        String path = BASE + "/" + preview.path("id").asText() + "/confirm";
        for (Object input : List.of(Map.of(), Map.of("assignments", Map.of()), Map.of("assignments", Map.of("unknown", CLUB + "-dog")), assignments(preview, ""))) {
            call(body(post(path), input), CLUB, "ADMIN").andExpect(status().isBadRequest());
        }
        assertThat(json(call(get(BASE), CLUB, "ADMIN"))).isEmpty();
        json(call(body(post(path), assignments(preview, CLUB + "-dog")), CLUB, "ADMIN"));
        call(body(post(path), assignments(preview, CLUB + "-second")), CLUB, "ADMIN").andExpect(status().isConflict());
        var duplicate = preview(CompetitionParsersTest.FCAG.replace("</tbody>", CompetitionParsersTest.FCAG.substring(CompetitionParsersTest.FCAG.indexOf("<tr><td>"), CompetitionParsersTest.FCAG.indexOf("</tbody>")).replace("0123", "4567") + "</tbody>"));
        var assignmentMap = new HashMap<String,String>(); duplicate.path("rows").forEach(row -> assignmentMap.put(row.at("/entry/key").asText(), CLUB + "-dog"));
        call(body(post(BASE + "/" + duplicate.path("id").asText() + "/confirm"), Map.of("assignments", assignmentMap)), CLUB, "ADMIN").andExpect(status().isBadRequest());
        assertThat(json(call(get(BASE), CLUB, "ADMIN"))).hasSize(1);
    }

    @Test void T_STATS_31_savedHtmlAboveOneMegabytePassesTransportButSourceLimitStillApplies() throws Exception {
        assertThat(preview(CompetitionParsersTest.FCAG + "<!--" + "x".repeat(1_100_000) + "-->").path("rows")).hasSize(1);
        call(body(post(BASE + "/preview"), Map.of("source", "FCAG", "sourceUrl", CompetitionParsersTest.FCAG_URL,
                "content", CompetitionParsersTest.FCAG + "x".repeat(CompetitionValidation.MAX_BYTES))), CLUB, "ADMIN").andExpect(status().isBadRequest());
    }

    @Test void T_STATS_47_progressReadsPriorYearsAndCurrentLicenceWithinTheTenant() throws Exception {
        clock.setInstant(java.time.Instant.parse("2026-11-16T10:00:00Z"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB + "-dog")),
                new Update().set("licenses", List.of(Map.of("organisation", "RSCE", "number", "0123", "grade", "II"))), Dog.class);
        var row = new FlowResultsParser().parse(FlowResultsParser.HEADER + "\n"
                + CompetitionParsersTest.flowRow("old", "AGILITY", "PA").replace("2026-10-10", "2025-12-31"), CompetitionParsersTest.FLOW_URL).entries().getFirst();
        for (String tenant : List.of(CLUB, OTHER)) {
            mongo.insert(new com.agilityhub.core.clubs.statistics.persistence.CompetitionImportRecord(tenant + "-history", tenant,
                    CompetitionModels.Source.FLOW_AGILITY, CompetitionParsersTest.FLOW_URL, "2025", clock.instant(), 1,
                    List.of(new CompetitionModels.SavedEntry(CLUB + "-dog", row))));
        }
        var stats = json(call(get("/api/v1/me/dogs/" + CLUB + "-dog/competition-statistics").param("year", "2026"), CLUB, "MEMBER"));
        assertThat(stats.path("results")).isEmpty();
        assertThat(stats.path("rsceProgress")).hasSize(1);
        assertThat(stats.at("/rsceProgress/0/currentGrade").asText()).isEqualTo("G2");
        assertThat(stats.at("/rsceProgress/0/gradeSource").asText()).isEqualTo("LICENSE");
        assertThat(stats.at("/rsceProgress/0/promotionRoutes/0/targets/0/earned").asInt()).isEqualTo(1);
        assertThat(stats.at("/rsceProgress/0/promotionRoutes/0/targets/0/remaining").asInt()).isEqualTo(2);
        assertThat(stats.at("/rsceProgress/0/championshipRoutes/0/targets/0/earned").asInt()).isZero();
        assertThat(stats.at("/rsceProgress/0/annualSummaries")).hasSize(3);
        assertThat(stats.at("/rsceProgress/0/annualSummaries/0/year").asInt()).isEqualTo(2024);
        assertThat(stats.at("/rsceProgress/0/annualSummaries/1/year").asInt()).isEqualTo(2025);
        assertThat(stats.at("/rsceProgress/0/annualSummaries/1/summaries/0/runs").asInt()).isEqualTo(1);
        assertThat(stats.at("/rsceProgress/0/annualSummaries/1/summaries/0/clearRatePercent").asDouble()).isEqualTo(100.0);
        assertThat(stats.at("/rsceProgress/0/annualSummaries/2/summaries/0/runs").asInt()).isZero();
    }

}
