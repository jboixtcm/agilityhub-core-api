package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.bookings.application.AttendanceContractAccess;
import com.agilityhub.core.clubs.census.application.DemoSeedCommand;
import com.agilityhub.core.clubs.followup.application.FollowupContractAccess;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.definition.ClubDefinitionCodec;
import com.agilityhub.core.platform.application.definition.ClubDefinitions;
import com.agilityhub.core.platform.application.jobs.JobAdminService;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.SnapshotSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E5-T20 step 3 (CONVENCIONS_API §4, amended 26-09 after E4-W05 question 1): every universal list publishes the `fields` keys it
 * accepts (`x-fields`, next to `x-filterable` and `x-sortable`), and accepts no other. The published keys are compared with the
 * runtime allowlist of each list: the union over ADMIN and INSTRUCTOR with every module on, so a key outside `x-fields` is
 * `400 INVALID_FILTER` for every role. E5-T22 step 4: every universal list honours `fields`, on the Cànic demo (same Spring
 * context as {@code DemoScenarioSeedIT}).
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
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
    /** The universal lists that are still stubs (S10): they validate the query, `fields` included, and then answer 501. */
    static final Set<String> STUBS = Set.of("/api/v1/followup", "/api/v1/attendances");
    /** The contract-only lists of the platform console: no `fields` parameter and no `x-fields` until they are implemented. */
    static final List<String> CONTRACT_ONLY = List.of("/api/v1/platform/audit-entries", "/api/v1/platform/erasure-requests", "/api/v1/platform/security-events");
    static final String CANIC_HOST = "app.agilitycanic.cat";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired ListEngine lists;
    @Autowired HostTenantResolver hosts;
    @Autowired ClubDefinitions definitions;
    @Autowired ClubDefinitionCodec codec;
    @Autowired DemoSeedCommand demo;

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

    /**
     * E5-T22 step 4 (CONVENCIONS_API §4, review E5-T20 #3). On every universal list of the committed snapshot:
     * - its item schemas require only the row id, which is in its `x-fields`;
     * - for ADMIN and INSTRUCTOR, `fields=<key>` (each key of `x-fields` the role may read) answers the whole items of the same
     *   page cut to the row id and that key: an unrequested key is left out, a requested one keeps its value or its `null`;
     * - the sparse page conforms to the snapshot.
     * The stubs answer 501 once `fields` is valid, and the contract-only lists publish neither `fields` nor `x-fields`.
     * Data: the Cànic demo with its scenario (seeds/demo-canic.yaml), a job run and a member's audit entry, every module on.
     */
    @Test void CONVENCIONS_API_4_withFieldsEveryUniversalListSendsTheRowIdAndTheRequestedKeysOnly() throws Exception {
        String canic = demoClub();
        var api = mapper.readTree(java.nio.file.Path.of("docs/openapi/openapi.json").toFile());
        var checked = new TreeMap<String, List<String>>(); var problems = new ArrayList<String>();
        for (var entry : (Iterable<Map.Entry<String, JsonNode>>) api.path("paths")::fields) {
            String path = entry.getKey(); var list = entry.getValue().path("get");
            if (CONTRACT_ONLY.contains(path)) {
                assertThat(list.path("parameters").findValuesAsText("name")).as(path).contains("filter");
                if (list.path("parameters").findValuesAsText("name").contains("fields") || list.has("x-fields")) { problems.add(path + ": contract only, but it publishes fields"); }
                continue;
            }
            if (!universalList(path, list)) { continue; }
            var keys = new ArrayList<String>(); list.path("x-fields").forEach(key -> keys.add(key.asText()));
            String page = list.at("/responses/200/content/application~1json/schema/$ref").asText().replace("#/components/schemas/", "");
            var items = SnapshotSchemas.schema(page).at("/properties/items/items");
            var itemSchemas = (items.has("anyOf") ? items.path("anyOf") : items).findValuesAsText("$ref").stream().map(ref -> ref.replace("#/components/schemas/", "")).toList();
            assertThat(itemSchemas).as(path).isNotEmpty();
            var required = SnapshotSchemas.required(itemSchemas.getFirst());
            String rowId = required.size() == 1 ? required.iterator().next()
                    : List.of("id", "registrationId", "runId").stream().filter(required::contains).findFirst().orElse("?");
            for (String schema : itemSchemas) {
                if (!SnapshotSchemas.required(schema).equals(Set.of(rowId))) { problems.add(path + " " + schema + " requires " + SnapshotSchemas.required(schema) + ", not only the row id"); }
            }
            if (!keys.contains(rowId)) { problems.add(path + " x-fields " + keys + " without the row id " + rowId); }
            String url = path.replace("{name}", "cleanup").replace("{id}", path.startsWith("/api/v1/members/") ? auditedMember(canic) : registeredActivity(canic));
            for (String role : List.of("ADMIN", "INSTRUCTOR")) {
                var whole = read(url, null, canic, role);
                if (whole.status() == 403 && role.equals("INSTRUCTOR")) { continue; }
                if (STUBS.contains(path)) {
                    assertThat(whole.status()).as(path).isEqualTo(501);
                    assertThat(read(url, keys.getLast(), canic, role).body().path("code").asText()).as(path).isEqualTo("NOT_IMPLEMENTED");
                    assertThat(read(url, "unknownKey", canic, role).body().path("code").asText()).as(path).isEqualTo("INVALID_FILTER");
                    checked.put(path + " " + role, List.of("501")); continue;
                }
                assertThat(whole.status()).as(path + " " + role + " " + whole.body()).isEqualTo(200);
                // Up to 200 whole rows conform (the rows of the same instant come in `_id` order, a random UUID).
                problems.addAll(SnapshotSchemas.violations(read(url, null, canic, role, "200").body(), page).stream().limit(3).map(v -> path + " " + role + " whole rows: " + v).toList());
                if (role.equals("ADMIN")) { assertThat(whole.body().path("items")).as(path + " has rows to check").isNotEmpty(); }
                // `x-fields` is the union over the roles (E5-T20): a key outside the role's own allowlist is 400 INVALID_FILTER.
                var allowed = OWN_LISTS.containsKey(path) ? OWN_LISTS.get(path) : allowed(ENGINE_LISTS.get(path), canic, role);
                var readKeys = new ArrayList<String>();
                for (String key : keys) {
                    var sparse = read(url, key, canic, role);
                    if (!allowed.contains(key)) {
                        if (sparse.status() != 400 || !sparse.body().path("code").asText().equals("INVALID_FILTER")) { problems.add(path + " " + role + " fields=" + key + " (not the role's): " + sparse.status()); }
                        continue;
                    }
                    if (sparse.status() != 200) { problems.add(path + " " + role + " fields=" + key + ": " + sparse.status() + " " + sparse.body()); continue; }
                    problems.addAll(SnapshotSchemas.violations(sparse.body(), page).stream().limit(2).map(v -> path + " " + role + " fields=" + key + ": " + v).toList());
                    var expected = mapper.createArrayNode();
                    whole.body().path("items").forEach(item -> {
                        var cut = mapper.createObjectNode(); item.fields().forEachRemaining(field -> { if (field.getKey().equals(rowId) || field.getKey().equals(key)) { cut.set(field.getKey(), field.getValue()); } });
                        expected.add(cut);
                    });
                    if (!sparse.body().path("items").equals(expected)) { problems.add(path + " " + role + " fields=" + key + ": " + sparse.body().path("items").get(0) + " instead of " + expected.get(0)); }
                    readKeys.add(key);
                }
                assertThat(readKeys).as(path + " " + role + " keys read").isNotEmpty();
                checked.put(path + " " + role, readKeys);
            }
        }
        assertThat(problems).isEmpty();
        var lists = new TreeSet<String>(); checked.keySet().forEach(key -> lists.add(key.substring(0, key.indexOf(' '))));
        var expected = new TreeSet<String>(ENGINE_LISTS.keySet()); expected.addAll(OWN_LISTS.keySet());
        assertThat(lists).as("every universal list is checked").containsExactlyElementsOf(expected);
        assertThat(checked).containsKeys("/api/v1/members INSTRUCTOR", "/api/v1/ring-blocks INSTRUCTOR", "/api/v1/class-sessions INSTRUCTOR");
    }

    record Response(int status, JsonNode body) { }
    Response read(String url, String fields, String club, String role) throws Exception { return read(url, fields, club, role, "20"); }
    Response read(String url, String fields, String club, String role, String size) throws Exception {
        var request = get(url).header("Host", CANIC_HOST).param("size", size)
                .with(jwt().jwt(j -> j.subject("list-fields-" + role.toLowerCase()).claim("clubId", club)).authorities(new SimpleGrantedAuthority("ROLE_" + role)));
        if (fields != null) { request.param("fields", fields); }
        var response = mvc.perform(request).andReturn().getResponse();
        return new Response(response.getStatus(), mapper.readTree(response.getContentAsString()));
    }

    /** The Cànic with its demo and scenario (as {@code DemoScenarioSeedIT}), every module on, a job run and a member's audit entry. */
    String demoClub() throws Exception {
        wipeDatabaseKeepingBootstrap(); hosts.invalidate(); clock.setInstant(java.time.Instant.parse("2026-09-09T10:00:00Z"));
        String canic = definitions.apply(codec.read(java.nio.file.Path.of("seeds/club-canic.yaml")), false).id();
        demo.run(new DefaultApplicationArguments("--club=canic", "--seed=42", "--week-start=2026-09-14"));
        var tree = (ObjectNode) mapper.valueToTree(clubs.findById(canic).orElseThrow());
        tree.set("modules", mapper.valueToTree(Module.values()));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(canic);
        mvc.perform(post("/api/v1/jobs/cleanup/trigger").header("Host", CANIC_HOST).contentType("application/json").content("{\"dryRun\":true}")
                .with(jwt().jwt(j -> j.subject("list-fields-admin").claim("clubId", canic)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().isOk());
        return canic;
    }
    String registeredActivity(String club) {
        return mongo.findOne(Query.query(Criteria.where("clubId").is(club)), org.bson.Document.class, "activity_registrations").getString("activityId");
    }
    String auditedMember(String club) {
        var entry = mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("memberId").ne(null)), org.bson.Document.class, "audit_entries");
        assertThat(entry).as("a member's audit entry in the demo").isNotNull();
        return entry.getString("memberId");
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
            try { union.addAll(allowed(list, CLUB, role)); }
            catch (ApiException denied) { assertThat(role).as(list + " " + denied.code()).isEqualTo("INSTRUCTOR"); }
        }
        return union;
    }
    /** The keys the runtime allowlist of `list` accepts for `role` in `club`. */
    Set<String> allowed(String list, String club, String role) {
        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("list-fields-" + role).claim("clubId", club).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        try (var tenant = TenantContext.open(club)) { return Set.copyOf(lists.dataset(list).definition().fields()); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
