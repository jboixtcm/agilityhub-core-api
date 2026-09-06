package com.agilityhub.core.identity.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.identity.api.IdentityRequests.*;
import static com.agilityhub.core.identity.api.IdentityResponses.*;

@RestController
public class AuthController {
    private final com.agilityhub.core.identity.application.MagicLinkService magicLinks;
    private final com.agilityhub.core.shared.application.RateLimits limits;
    private final com.agilityhub.core.shared.application.SecurityEvents events;
    private final boolean local;
    public AuthController(com.agilityhub.core.identity.application.MagicLinkService magicLinks,
            com.agilityhub.core.shared.application.RateLimits limits, com.agilityhub.core.shared.application.SecurityEvents events,
            org.springframework.core.env.Environment environment) {
        this.magicLinks = magicLinks; this.limits = limits; this.events = events;
        this.local = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local"));
    }
    @PostMapping("/api/v1/auth/magic-link")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirements
    @Operation(summary = "Request a login or password-reset magic link",
            description = "ANON. R-01-04: neutral 202 whether the account/membership exists or not; no email enumeration. "
                    + "Shares the configured authentication IP quota with /oauth2/token.",
            responses = {@ApiResponse(responseCode = "202", description = "Request accepted", content = @Content),
                    @ApiResponse(responseCode = "429", description = "RATE_LIMITED; Retry-After in seconds")})
    public ResponseEntity<Void> magicLink(@Valid @RequestBody MagicLinkRequest request, jakarta.servlet.http.HttpServletRequest http) {
        String email = com.agilityhub.core.identity.domain.Email.normalize(request.email());
        long retry = Math.max(limits.retryAfter(com.agilityhub.core.shared.application.RateLimits.Route.MAGIC_LINK_EMAIL,
                com.agilityhub.core.identity.application.TokenService.digest(email)),
                limits.retryAfter(com.agilityhub.core.shared.application.RateLimits.Route.MAGIC_LINK_IP, http.getRemoteAddr()));
        if (retry > 0) {
            events.record(com.agilityhub.core.shared.application.SecurityEvents.Type.RATE_LIMITED, null,
                    com.agilityhub.core.shared.application.TenantContext.current());
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.RATE_LIMITED, java.util.Map.of("retryAfter", retry));
        }
        String host = local && http.getHeader("X-Club-Host") != null ? http.getHeader("X-Club-Host") : http.getHeader("Host");
        magicLinks.request(email, com.agilityhub.core.identity.persistence.MagicLinkToken.Purpose.valueOf(request.purpose().name()),
                request.clientId(), request.redirectUri(), host, http.getRemoteAddr(), http.getHeader("User-Agent"));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/v1/auth/handoff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create a one-use code for switching apps",
            description = "Account token with club context. R-01-13: code lasts 60 seconds and is bound to targetClientId.",
            responses = {@ApiResponse(responseCode = "201", description = "Code and destination login URL"),
                    @ApiResponse(responseCode = "403", description = "NO_MEMBERSHIP for the destination role")})
    public HandoffResponse handoff(@Valid @RequestBody HandoffRequest request) { throw new UnsupportedOperationException(); }
}
