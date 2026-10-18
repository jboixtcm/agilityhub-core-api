package com.agilityhub.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-15-29 (with T-08-47, T-09-24), INC-40 (E11-T10): the hand-written route fixture of the E5 contract tests
 * (`fixtures/contracts/e5-routes.json`, read by {@code E5ContractIT}) is the §6 tables of S08, S09 and S15, both ways, and so is
 * the committed snapshot's set of operations under those routes: a route added to a §6 table or to the API but not to the
 * fixture (or the reverse) fails here instead of passing unnoticed.
 */
class E5RouteParityTest {
    static final Map<String, String> SPECS = Map.of("S08", "docs/specs/S08-reserves-de-classes-i-llista-espera.md",
            "S09", "docs/specs/S09-entrenaments-lliures.md", "S15", "docs/specs/S15-processos-programats.md");
    /** `| GET | `/a`, `/b` | …`: the method cell, then every backticked path of the route cell. */
    static final Pattern ROW = Pattern.compile("^\\|\\s*([A-Z]+)\\s*\\|([^|]*)\\|");
    static final Pattern PATH = Pattern.compile("`(/[^`]*)`");
    /** S09 §6 repeats S06's `/ring-blocks` routes (their owner, E4): they belong to `e4-routes.json`, not to this fixture. */
    static final String S06_OWNED = "/api/v1/ring-blocks";
    /**
     * The companions of a universal list (CONVENCIONS_API §4): its `filter-values` (E5-T29) and the S09 row's `/export`; §6 names
     * the list only.
     */
    static final Set<String> COMPANIONS = Set.of("GET /api/v1/bookings/filter-values", "GET /api/v1/training-bookings/filter-values",
            "GET /api/v1/training-bookings/export");
    final ObjectMapper mapper = new ObjectMapper();

    static Set<String> specRoutes() throws Exception {
        var routes = new TreeSet<String>();
        for (String file : SPECS.values()) {
            boolean api = false;
            for (String line : Files.readAllLines(Path.of(file))) {
                if (line.startsWith("## ")) { api = line.startsWith("## 6."); continue; }
                var row = ROW.matcher(line);
                if (!api || !row.find() || !Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(row.group(1))) { continue; }
                var paths = PATH.matcher(row.group(2));
                while (paths.find()) { routes.add(row.group(1) + " /api/v1" + paths.group(1)); }
            }
        }
        return routes;
    }
    Set<String> fixtureRoutes(String name) throws Exception {
        var routes = new TreeSet<String>();
        for (JsonNode route : mapper.readTree(Path.of("src/test/resources/fixtures/contracts/" + name).toFile())) {
            routes.add(route.path("method").asText() + " " + route.path("path").asText());
        }
        return routes;
    }
    private static String path(String route) { return route.substring(route.indexOf(' ') + 1); }
    /** A route's family: its path without the trailing parameters (`/seat-holds/{id}` → `/seat-holds`); everything under it is the spec's. */
    private static String family(String route) { return path(route).replaceAll("(/\\{[^}]+})+$", ""); }

    /** T-15-29, INC-40 (E11-T10): the fixture is exactly the S08/S09/S15 §6 routes (less S06's) plus the universal-list companions. */
    @Test void T_15_29_INC_40_theE5FixtureIsTheSection6TablesBothWays() throws Exception {
        var spec = specRoutes();
        assertThat(spec).as("parsed §6 rows").hasSizeGreaterThanOrEqualTo(30);
        var owned = spec.stream().filter(route -> path(route).startsWith(S06_OWNED)).toList();
        assertThat(owned).as("S06's routes in S09 §6").isNotEmpty();
        assertThat(fixtureRoutes("e4-routes.json")).as("S06's routes are in their own fixture").containsAll(owned);
        var expected = new TreeSet<>(spec); owned.forEach(expected::remove); expected.addAll(COMPANIONS);
        assertThat(fixtureRoutes("e5-routes.json")).containsExactlyElementsOf(expected);
    }

    /**
     * T-15-29, INC-40 (E11-T10): the snapshot (`docs/openapi/openapi.json`) publishes every fixture route, and nothing else under
     * the §6 route families of S08, S09 and S15 (S06's `/ring-blocks` aside).
     */
    @Test void T_15_29_INC_40_theSnapshotPublishesTheFixtureRoutesAndNoOtherUnderTheirFamilies() throws Exception {
        var fixture = fixtureRoutes("e5-routes.json");
        var families = new TreeSet<String>();
        for (String route : specRoutes()) { if (!path(route).startsWith(S06_OWNED)) { families.add(family(route)); } }
        var published = new TreeSet<String>();
        var paths = mapper.readTree(Path.of("docs/openapi/openapi.json").toFile()).path("paths");
        paths.fieldNames().forEachRemaining(path -> paths.path(path).fieldNames().forEachRemaining(method -> {
            if (Set.of("get", "post", "put", "patch", "delete").contains(method)
                    && families.stream().anyMatch(family -> path.equals(family) || path.startsWith(family + "/"))) {
                published.add(method.toUpperCase(Locale.ROOT) + " " + path);
            }
        }));
        assertThat(published).containsExactlyElementsOf(fixture);
    }
}
