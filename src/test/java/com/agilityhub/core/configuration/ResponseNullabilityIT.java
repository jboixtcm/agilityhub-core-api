package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.census.application.DemoSeedCommand;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.definition.ClubDefinitionCodec;
import com.agilityhub.core.platform.application.definition.ClubDefinitions;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.SnapshotSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * INC-08 (E11-T02): a response sends `null` only where its published schema says `nullable`; an optional property without a
 * value is left out. The Cànic demo (seeds/demo-canic.yaml, every module on) is read through the D-screens' GET routes, as an
 * ADMIN, and every body is checked against the operation's schema of the committed snapshot: any `null` the contract does not
 * allow is a failure (the reproduction of INC-08 was `GET /parameters` and `GET /members/{id}/signup`).
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class ResponseNullabilityIT extends AbstractIntegrationTest {
    static final String HOST = "app.agilitycanic.cat";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs; @Autowired HostTenantResolver hosts;
    @Autowired ClubDefinitions definitions; @Autowired ClubDefinitionCodec codec; @Autowired DemoSeedCommand demo;

    String id(String collection, Criteria criteria) {
        var row = mongo.findOne(Query.query(criteria), Document.class, collection);
        assertThat(row).as(collection + " " + criteria.getCriteriaObject()).isNotNull();
        return row.get("_id").toString();
    }

    @Test void INC_08_everyNullTheDemoAnswersIsDeclaredNullable() throws Exception {
        wipeDatabaseKeepingBootstrap(); hosts.invalidate(); clock.setInstant(java.time.Instant.parse("2026-09-09T10:00:00Z"));
        String canic = definitions.apply(codec.read(java.nio.file.Path.of("seeds/club-canic.yaml")), false).id();
        demo.run(new DefaultApplicationArguments("--club=canic", "--seed=42", "--week-start=2026-09-14"));
        var tree = (ObjectNode) mapper.valueToTree(clubs.findById(canic).orElseThrow());
        tree.set("modules", mapper.valueToTree(Module.values()));
        clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(canic);
        var club = Criteria.where("clubId").is(canic);
        String pending = id("members", Criteria.where("clubId").is(canic).and("status").is("PENDING"));
        String sepa = id("members", Criteria.where("clubId").is(canic).and("status").is("ACTIVE").and("paymentMethod.type").is("SEPA_DD"));
        String manual = id("members", Criteria.where("clubId").is(canic).and("status").is("ACTIVE").and("paymentMethod.type").is("MANUAL"));
        String dog = id("dogs", Criteria.where("clubId").is(canic).and("status").is("ACTIVE"));
        var routes = new LinkedHashMap<String, String>();
        routes.put("/api/v1/parameters", "/api/v1/parameters");
        routes.put("/api/v1/members/" + pending + "/signup", "/api/v1/members/{id}/signup");
        for (String member : List.of(sepa, manual)) {
            routes.put("/api/v1/members/" + member + "/overview", "/api/v1/members/{id}/overview");
            routes.put("/api/v1/members/" + member + "/notification-preferences", "/api/v1/members/{id}/notification-preferences");
        }
        routes.put("/api/v1/dogs/" + dog, "/api/v1/dogs/{id}");
        routes.put("/api/v1/plans", "/api/v1/plans");
        routes.put("/api/v1/levels", "/api/v1/levels");
        routes.put("/api/v1/rings", "/api/v1/rings");
        routes.put("/api/v1/instructors", "/api/v1/instructors");
        routes.put("/api/v1/club", "/api/v1/club");
        routes.put("/api/v1/jobs", "/api/v1/jobs");
        routes.put("/api/v1/dashboard", "/api/v1/dashboard");
        var paths = new ObjectMapper().readTree(java.nio.file.Path.of("docs/openapi/openapi.json").toFile()).path("paths");
        var problems = new ArrayList<String>();
        for (var route : routes.entrySet()) {
            var schema = paths.path(route.getValue()).at("/get/responses/200/content/application~1json/schema");
            String ref = schema.path("$ref").asText(schema.path("oneOf").path(0).path("$ref").asText());
            assertThat(ref).as(route.getValue() + " publishes a schema").startsWith("#/components/schemas/");
            var response = mvc.perform(get(route.getKey()).header("Host", HOST)
                    .with(jwt().jwt(j -> j.subject("nullability-admin").claim("clubId", canic)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andReturn().getResponse();
            assertThat(response.getStatus()).as(route.getKey() + " " + response.getContentAsString()).isEqualTo(200);
            JsonNode body = mapper.readTree(response.getContentAsString());
            SnapshotSchemas.violations(body, ref.substring("#/components/schemas/".length())).stream()
                    .filter(message -> message.contains("null found"))
                    .forEach(message -> problems.add(route.getValue() + " " + message));
        }
        assertThat(problems).isEmpty();
    }
}
