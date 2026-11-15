package com.agilityhub.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * REL-06 (owner decision of 13 Nov 2026): every published operation that needs a bearer is in a data-driven route fixture
 * ({@code e2…e10-routes.json}, or {@code role-matrix-routes.json} that {@link RoleMatrixIT} reads), so a new bearer route without
 * its expected roles fails here; and each role-matrix entry names the matrix row it reads, with a recorded discrepancy
 * ({@code current}) that still differs from what the matrix gives.
 */
class RoleMatrixCoverageTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/contracts");
    private static final Set<String> ROLES = Set.of("MEMBER", "INSTRUCTOR", "ADMIN", "AGILITYHUB_ADMIN");

    private static boolean bearer(JsonNode api, JsonNode operation) {
        JsonNode requirement = operation.has("security") ? operation.path("security") : api.path("security");
        if (requirement.isEmpty()) { return false; }
        for (var alternative : requirement) {
            if (!alternative.has("bearer") && !alternative.has(E9ContractConfiguration.ACCESS_TOKEN_QUERY)) { return false; }
        }
        return true;
    }

    @Test void REL_06_everyPublishedBearerOperationIsInARouteFixture() throws Exception {
        var covered = new TreeSet<String>();
        try (var files = Files.list(FIXTURES)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith("-routes.json")).toList()) {
                for (JsonNode route : MAPPER.readTree(file.toFile())) { covered.add(route.path("method").asText().toUpperCase() + " " + route.path("path").asText()); }
            }
        }
        JsonNode api = MAPPER.readTree(Path.of("docs/openapi/openapi.json").toFile());
        var missing = new TreeSet<String>();
        int bearerOperations = 0;
        for (var path : api.path("paths").properties()) {
            for (var method : path.getValue().properties()) {
                if (!Set.of("get", "post", "put", "patch", "delete").contains(method.getKey()) || !bearer(api, method.getValue())) { continue; }
                bearerOperations++;
                String label = method.getKey().toUpperCase() + " " + path.getKey();
                if (!covered.contains(label)) { missing.add(label); }
            }
        }
        assertThat(bearerOperations).as("the published bearer operations were found").isGreaterThan(400);
        assertThat(missing).as("bearer operations without expected roles in a route fixture (add them to role-matrix-routes.json)").isEmpty();
    }

    @Test void REL_06_eachRoleMatrixEntryReadsAMatrixRowAndADiscrepancyStillDiffers() throws Exception {
        JsonNode routes = MAPPER.readTree(FIXTURES.resolve("role-matrix-routes.json").toFile());
        assertThat(routes.size()).isPositive();
        for (JsonNode route : routes) {
            String label = route.path("method").asText() + " " + route.path("path").asText() + " " + route.path("body");
            var roles = strings(route.path("roles"));
            assertThat(roles).as(label).isNotEmpty();
            assertThat(ROLES).as(label).containsAll(roles);
            assertThat(route.path("scope").asText()).as(label).isIn("CLUB", "GLOBAL");
            assertThat(route.path("matrix").asText()).as(label + " names the matrix row it reads").isNotBlank();
            if (route.has("current")) {
                assertThat(ROLES).as(label).containsAll(strings(route.path("current")));
                assertThat(strings(route.path("current"))).as(label + ": a resolved discrepancy drops `current`").isNotEqualTo(roles);
                assertThat(route.path("question").asText()).as(label + " says what the owner must decide").isNotBlank();
            }
            if (route.has("currentImpersonation")) {
                assertThat(route.path("currentImpersonation").asBoolean()).as(label + ": a resolved discrepancy drops `currentImpersonation`")
                        .isNotEqualTo(route.path("impersonation").asBoolean());
                assertThat(route.path("question").asText()).as(label + " says what the owner must decide").isNotBlank();
            }
            if (route.path("impersonation").asBoolean()) { assertThat(roles).as(label + ": rule 3, the impersonation token is the member").contains("MEMBER"); }
        }
    }

    private static Set<String> strings(JsonNode array) {
        var values = new TreeSet<String>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
