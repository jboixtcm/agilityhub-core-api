package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.bookings.application.AttendanceContractAccess;
import com.agilityhub.core.clubs.followup.application.FollowupContractAccess;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.JobAdminService;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E5-T20 step 3 (CONVENCIONS_API §4, amended 26-09 after E4-W05 question 1): every universal list publishes the `fields` keys it
 * accepts (`x-fields`, next to `x-filterable` and `x-sortable`), and accepts no other. The published keys are compared with the
 * runtime allowlist of each list: the union over ADMIN and INSTRUCTOR with every module on, so a key outside `x-fields` is
 * `400 INVALID_FILTER` for every role.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ListFieldsContractIT extends AbstractIntegrationTest {
    static final String CLUB = "list-fields-a";
    /** Operation path → the `ListEngine` list it serves. */
    static final Map<String, String> ENGINE_LISTS = Map.ofEntries(Map.entry("/api/v1/activities", "activities"),
            Map.entry("/api/v1/activities/{id}/registrations", "activity-registrations"), Map.entry("/api/v1/members", "members"),
            Map.entry("/api/v1/dogs", "dogs"), Map.entry("/api/v1/bookings", "bookings"), Map.entry("/api/v1/weeks", "weeks"),
            Map.entry("/api/v1/class-sessions", "class-sessions"), Map.entry("/api/v1/ring-blocks", "ring-blocks"),
            Map.entry("/api/v1/training-bookings", "training-bookings"), Map.entry("/api/v1/audit-entries", "audit-entries"),
            Map.entry("/api/v1/members/{id}/audit-entries", "audit-entries"));
    /** Operation path → the allowlist of a list that is not a `ListEngine` provider. */
    static final Map<String, Set<String>> OWN_LISTS = Map.of("/api/v1/followup", FollowupContractAccess.FOLLOWUP.fields(),
            "/api/v1/attendances", AttendanceContractAccess.ATTENDANCES.fields(), "/api/v1/jobs/{name}/runs", JobAdminService.RUN_FIELDS);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired ListEngine lists;

    @BeforeEach void club() {
        mongo.remove(Query.query(Criteria.where("_id").is(CLUB)), Club.class);
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(CLUB, "list-fields-a.example.test"));
        tree.set("modules", mapper.valueToTree(Module.values()));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(CLUB);
    }

    @Test void CONVENCIONS_API_4_everyUniversalListPublishesExactlyTheFieldsItAccepts() throws Exception {
        var api = mapper.readTree(mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var published = new TreeMap<String, List<String>>();
        api.path("paths").fields().forEachRemaining(path -> {
            var list = path.getValue().path("get");
            if (universalList(path.getKey(), list)) {
                var keys = new ArrayList<String>(); list.path("x-fields").forEach(key -> keys.add(key.asText()));
                published.put(path.getKey(), keys);
            }
        });
        var expected = new TreeSet<String>(ENGINE_LISTS.keySet()); expected.addAll(OWN_LISTS.keySet());
        assertThat(published.keySet()).as("the universal lists of the contract").containsExactlyElementsOf(expected);
        var problems = new ArrayList<String>();
        published.forEach((path, keys) -> {
            var accepted = OWN_LISTS.containsKey(path) ? new TreeSet<>(OWN_LISTS.get(path)) : accepted(ENGINE_LISTS.get(path));
            if (keys.isEmpty()) { problems.add(path + ": no x-fields"); }
            if (new HashSet<>(keys).size() != keys.size()) { problems.add(path + ": duplicated x-fields " + keys); }
            if (!new TreeSet<>(keys).equals(accepted)) { problems.add(path + ": x-fields " + new TreeSet<>(keys) + " but accepts " + accepted); }
        });
        assertThat(problems).isEmpty();
        assertThat(api.at("/paths/~1api~1v1~1members/get/parameters").findValuesAsText("description").toString()).contains("x-fields");
    }

    /** A GET that answers a page through the universal list contract: filters, `fields`, and no deferred contract. */
    static boolean universalList(String path, JsonNode operation) {
        return !operation.isMissingNode() && operation.has("x-filterable") && operation.path("parameters").findValuesAsText("name").contains("fields")
                && !path.endsWith("/export") && !path.endsWith("/filter-values") && !operation.path("description").asText().startsWith("Contract only");
    }

    /** The keys the runtime allowlist accepts for ADMIN and for INSTRUCTOR, with every module on. */
    Set<String> accepted(String list) {
        var union = new TreeSet<String>();
        for (String role : List.of("ADMIN", "INSTRUCTOR")) {
            var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("list-fields-" + role).claim("clubId", CLUB).build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            try (var tenant = TenantContext.open(CLUB)) { union.addAll(lists.dataset(list).definition().fields()); }
            catch (ApiException denied) { assertThat(role).as(list + " " + denied.code()).isEqualTo("INSTRUCTOR"); }
            finally { SecurityContextHolder.clearContext(); }
        }
        return union;
    }
}
