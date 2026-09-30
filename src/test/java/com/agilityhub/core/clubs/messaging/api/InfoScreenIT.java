package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * T-11-27 (R-11-14, R-11-17, S05 R-05-22), screen 30 «Info» on the existing contracts (E7-T03 step 8, no new entity):
 * `GET /faq-entries` read by MEMBER and INSTRUCTOR returns only the active entries with `category`, `question` and `answer`
 * resolved in the reader's language with the club's fallback, grouped by category in the order of each category's lowest
 * `order`, without the translation maps; `FAQ` off → `404 MODULE_DISABLED`. The 05-09 tabs «Normes» and the other active
 * pages come from `GET /club-pages?active=true`, which serves the member role the active pages only.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class InfoScreenIT extends AbstractIntegrationTest {
    static final String CLUB = "e7t03-info-a", HOST = "info-a.example.test";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo; @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;

    @BeforeEach void prepare() throws Exception {
        clock.setInstant(Instant.parse("2026-10-05T08:00:00Z"));
        club(List.of(Module.values()));
        for (String collection : List.of("faq_entries", "club_pages", "audit_entries", "domain_events")) { mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), collection); }
        faq(Map.of("ca", "Reserves de classe", "es", "Reservas de clase"), Map.of("ca", "Quantes classes puc reservar?"), Map.of("ca", "Dues per setmana."), 20, true);
        faq(Map.of("ca", "Convivència al club", "es", "Convivencia en el club"), Map.of("ca", "Puc venir amb més gent?", "es", "¿Puedo venir con más gente?"),
                Map.of("ca", "Sí.\nAvisa al club.", "es", "Sí.\nAvisa al club."), 0, true);
        faq(Map.of("ca", "Convivència al club", "es", "Convivencia en el club"), Map.of("ca", "El gos pot anar deslligat?", "es", "¿Puede ir el perro suelto?"),
                Map.of("ca", "Només a la pista."), 10, true);
        faq(Map.of("ca", "Convivència al club"), Map.of("ca", "Pregunta retirada"), Map.of("ca", "Ja no aplica."), 5, false);
        for (var page : List.of(List.of("RULES", "Normes del club", "true"), List.of("tarifes", "Tarifes", "false"))) {
            var body = mapper.createObjectNode().put("key", page.get(0)).put("active", Boolean.parseBoolean(page.get(2)));
            body.putObject("title").put("ca", page.get(1)); body.putObject("body").put("ca", "# " + page.get(1));
            call(as(post("/api/v1/club-pages").contentType("application/json").content(body.toString()), "ADMIN", "ca"), 201);
        }
    }
    private void club(List<Module> modules) {
        mongo.remove(Query.query(Criteria.where("_id").is(CLUB)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(CLUB, HOST));
        tree.set("modules", mapper.valueToTree(modules));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(CLUB); hosts.invalidate();
    }
    private void faq(Map<String, String> category, Map<String, String> question, Map<String, String> answer, int order, boolean active) throws Exception {
        var body = mapper.createObjectNode(); body.set("category", mapper.valueToTree(category)); body.set("question", mapper.valueToTree(question));
        body.set("answer", mapper.valueToTree(answer)); body.put("order", order); body.put("active", active);
        call(as(post("/api/v1/faq-entries").contentType("application/json").content(body.toString()), "ADMIN", "ca"), 201);
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role, String locale) {
        return request.header("Host", HOST).with(jwt().jwt(j -> j.subject("info-" + role).claim("clubId", CLUB).claim("locale", locale))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private JsonNode call(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }

    @Test void T_11_27_theFaqOfScreen30ForMembersAndInstructorsInTheirLanguageWithTheClubsFallback() throws Exception {
        for (String role : List.of("MEMBER", "INSTRUCTOR")) {
            var es = call(as(get("/api/v1/faq-entries"), role, "es"), 200);
            var questions = new ArrayList<String>(); es.path("items").forEach(i -> questions.add(i.path("question").asText()));
            // Only the active ones; «Convivència» first (its lowest order is 0), then «Reserves» (20); inside a category, by order.
            assertThat(questions).as(role).containsExactly("¿Puedo venir con más gente?", "¿Puede ir el perro suelto?", "Quantes classes puc reservar?");
            var first = es.path("items").get(0);
            assertThat(first.path("category").asText()).isEqualTo("Convivencia en el club"); assertThat(first.path("answer").asText()).isEqualTo("Sí.\nAvisa al club.");
            // The fallback of a missing translation is the club's default language (ca).
            assertThat(es.path("items").get(1).path("answer").asText()).isEqualTo("Només a la pista.");
            assertThat(es.path("items").get(2).path("category").asText()).isEqualTo("Reservas de clase");
            // The reader's projection: no translation maps, no audit.
            assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "category", "question", "answer", "order", "active", "version");
            assertThat(es.path("items")).allSatisfy(item -> assertThat(item.path("active").asBoolean()).isTrue());
            // A reader cannot ask for the inactive ones.
            call(as(get("/api/v1/faq-entries").param("includeInactive", "true"), role, "es"), 403);
        }
        assertThat(call(as(get("/api/v1/faq-entries"), "MEMBER", "ca"), 200).path("items").get(0).path("question").asText()).isEqualTo("Puc venir amb més gent?");
    }

    @Test void T_11_27_theOtherTabsAreTheActiveClubPagesAndFaqOffHidesTheFaq() throws Exception {
        var pages = call(as(get("/api/v1/club-pages").param("active", "true"), "MEMBER", "ca"), 200);
        assertThat(pages.path("items").findValuesAsText("key")).containsExactly("RULES");
        assertThat(pages.path("items").get(0).at("/title/ca").asText()).isEqualTo("Normes del club");
        // A member asking for the inactive ones still reads the active ones only.
        assertThat(call(as(get("/api/v1/club-pages").param("active", "false"), "MEMBER", "ca"), 200).path("items").findValuesAsText("key")).containsExactly("RULES");
        var modules = new ArrayList<>(List.of(Module.values())); modules.remove(Module.FAQ);
        club(modules);
        for (String role : List.of("MEMBER", "INSTRUCTOR", "ADMIN")) {
            assertThat(call(as(get("/api/v1/faq-entries"), role, "ca"), 404).path("code").asText()).isEqualTo("MODULE_DISABLED");
        }
        assertThat(call(as(get("/api/v1/club-pages").param("active", "true"), "MEMBER", "ca"), 200).path("items")).hasSize(1);
    }
}
