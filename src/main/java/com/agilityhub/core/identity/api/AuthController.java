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
    @PostMapping("/auth/magic-link")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirements
    @Operation(summary = "Request a login or password-reset magic link",
            description = "ANON. R-01-04: neutral 202 whether the account/membership exists or not; no email enumeration.",
            responses = {@ApiResponse(responseCode = "202", description = "Request accepted", content = @Content),
                    @ApiResponse(responseCode = "429", description = "RATE_LIMITED; Retry-After in seconds")})
    public ResponseEntity<Void> magicLink(@Valid @RequestBody MagicLinkRequest request) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/auth/handoff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create a one-use code for switching apps",
            description = "Account token with club context. R-01-13: code lasts 60 seconds and is bound to targetClientId.",
            responses = {@ApiResponse(responseCode = "201", description = "Code and destination login URL"),
                    @ApiResponse(responseCode = "403", description = "NO_MEMBERSHIP for the destination role")})
    public HandoffResponse handoff(@Valid @RequestBody HandoffRequest request) { throw new UnsupportedOperationException(); }
}
