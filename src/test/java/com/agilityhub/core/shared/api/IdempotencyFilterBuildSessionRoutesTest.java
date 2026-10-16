package com.agilityhub.core.shared.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E9-T06 (review of E9-T04 #1, INC-47's pattern): a keyed build-session write left in the filter's request transaction runs its
 * work once, so a write conflict or a join code already taken answers 500. Every keyed POST of `/build-sessions` in the published
 * contract (the start, the join and `finish`) runs in `BuildSessionService`'s own retried transaction
 * ({@link IdempotencyFilter#BUILD_SESSIONS}); the reads and the taps (`PATCH`, never keyed) are left out.
 */
class IdempotencyFilterBuildSessionRoutesTest {
    @Test void T_16_10_everyKeyedBuildSessionWriteRunsInItsOwnRetriedTransaction() throws Exception {
        var api = new ObjectMapper().readTree(Path.of("docs/openapi/openapi.json").toFile());
        var own = new TreeSet<String>(); var other = new TreeSet<String>();
        api.path("paths").properties().forEach(path -> {
            if (!path.getKey().startsWith("/api/v1/build-sessions")) { return; }
            path.getValue().properties().forEach(operation -> {
                String route = operation.getKey().toUpperCase() + " " + path.getKey();
                (IdempotencyFilter.BUILD_SESSIONS.matcher(route.replaceAll("\\{[^}]+}", "e9t06-id")).matches() ? own : other).add(route);
            });
        });
        assertThat(own).containsExactly("POST /api/v1/build-sessions", "POST /api/v1/build-sessions/join", "POST /api/v1/build-sessions/{id}/finish");
        assertThat(other).containsExactly("GET /api/v1/build-sessions/{id}", "GET /api/v1/build-sessions/{id}/events", "GET /api/v1/build-sessions/{id}/export",
                "PATCH /api/v1/build-sessions/{id}/obstacles/{obstacleId}");
        for (String route : List.of("POST /api/v1/build-sessions/x/finish/y", "POST /api/v1/build-sessions/x", "GET /api/v1/build-sessions",
                "POST /api/v1/build-sessions/join/x", "POST /api/v1/placements/x/build-sheet", "PUT /api/v1/build-sessions/x/finish")) {
            assertThat(IdempotencyFilter.BUILD_SESSIONS.matcher(route).matches()).as(route).isFalse();
        }
    }
}
