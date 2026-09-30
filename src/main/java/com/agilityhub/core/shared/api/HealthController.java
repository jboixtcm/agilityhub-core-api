package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.persistence.DatabaseProbe;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirements
public class HealthController {
    private static final Logger LOG = LoggerFactory.getLogger(HealthController.class);

    private final BuildProperties build;
    private final DatabaseProbe database;

    public HealthController(BuildProperties build, DatabaseProbe database) {
        this.build = build;
        this.database = database;
    }

    /**
     * INC-01 (E3-T06) and E5-T27 step 5 (A7-07): public, global, no tenant, locale or data. `UP` only when a Mongo `ping` answers
     * within 1 s; otherwise 503 with the same envelope and `status: DOWN`, and a WARN with the request's traceId. Round 3: an
     * `Authorization` header is never read (decoding a bearer reads the key ring from Mongo).
     */
    @GetMapping("/api/v1/health")
    @Operation(summary = "health", description = "ANON, global (no tenant, no locale, no data; an Authorization header is never read). 200 {status: UP} only when a Mongo ping answers within 1 s; "
            + "otherwise 503 with the same envelope and status DOWN. version and builtAt come from the build.",
            responses = @ApiResponse(responseCode = "200", description = "HealthResponse, status UP", useReturnTypeSchema = true))
    public ResponseEntity<HealthResponse> health(@io.swagger.v3.oas.annotations.Parameter(hidden = true) HttpServletRequest request) {
        if (database.up()) { return ResponseEntity.ok(new HealthResponse("UP", build.getVersion(), build.getTime())); }
        LOG.warn("Health DOWN: the database did not answer a ping within {} ms traceId={}", DatabaseProbe.TIMEOUT.toMillis(), RequestTraceFilter.traceId(request));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new HealthResponse("DOWN", build.getVersion(), build.getTime()));
    }
}
