package com.agilityhub.core.shared.api;

import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirements
public class HealthController {

    private final BuildProperties build;

    public HealthController(BuildProperties build) {
        this.build = build;
    }

    @GetMapping("/api/v1/health")
    public HealthResponse health() {
        return new HealthResponse("UP", build.getVersion(), build.getTime());
    }
}
