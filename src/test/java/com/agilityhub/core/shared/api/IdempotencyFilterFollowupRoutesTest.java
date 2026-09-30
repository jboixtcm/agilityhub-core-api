package com.agilityhub.core.shared.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E6-T03 round 5 (INC-47): a keyed follow-up write left in the filter's request transaction never retries its write conflict
 * and answers 500. So every POST, PUT and DELETE of the follow-up in the published contract (`/tasks`, `/attachments`,
 * `/followup`, `/dogs/{id}/observations`) runs in its own transaction ({@link IdempotencyFilter#FOLLOWUP}), including the
 * completion and the reopening, which a client may key, and the upload URL (the first grant of a database creates its
 * collection). Left out: the text edit (`PATCH`, keyed by its `version` and never filtered) and every read.
 */
class IdempotencyFilterFollowupRoutesTest {
    static final List<String> FOLLOWUP_PATHS = List.of("/api/v1/tasks", "/api/v1/attachments", "/api/v1/followup", "/api/v1/dogs/{id}/observations");

    @Test void E6_T03_everyFollowupWriteOfTheContractRunsInItsOwnRetriedTransaction() throws Exception {
        var api = new ObjectMapper().readTree(Path.of("docs/openapi/openapi.json").toFile());
        var own = new TreeSet<String>(); var request = new TreeSet<String>();
        api.path("paths").properties().forEach(path -> {
            if (FOLLOWUP_PATHS.stream().noneMatch(path.getKey()::startsWith)) { return; }
            path.getValue().properties().forEach(operation -> {
                String route = operation.getKey().toUpperCase() + " " + path.getKey();
                boolean matched = IdempotencyFilter.FOLLOWUP.matcher(route.replaceAll("\\{[^}]+}", "e6t03-id")).matches();
                (matched ? own : request).add(route);
            });
        });
        assertThat(own).containsExactly("DELETE /api/v1/attachments/{id}", "DELETE /api/v1/tasks/{id}", "POST /api/v1/attachments",
                "POST /api/v1/attachments/upload-url", "POST /api/v1/followup/read-all", "POST /api/v1/followup/{id}/read", "POST /api/v1/tasks",
                "POST /api/v1/tasks/{id}/completion", "POST /api/v1/tasks/{id}/reopening", "PUT /api/v1/dogs/{id}/observations");
        assertThat(request).as("reads and the text edit").allMatch(route -> route.startsWith("GET ") || route.equals("PATCH /api/v1/tasks/{id}"));
        assertThat(request).contains("PATCH /api/v1/tasks/{id}", "GET /api/v1/followup/unread-count");
        // The pattern names whole routes: nothing longer, no other method, no other context.
        for (String route : List.of("POST /api/v1/tasks/x/completion/y", "GET /api/v1/tasks", "PATCH /api/v1/tasks/x", "POST /api/v1/attachments/upload-url/x",
                "PUT /api/v1/attachments/uploads/x", "POST /api/v1/followup/x/read/y", "PUT /api/v1/dogs/x/instructor-note", "POST /api/v1/bookings")) {
            assertThat(IdempotencyFilter.FOLLOWUP.matcher(route).matches()).as(route).isFalse();
        }
    }
}
