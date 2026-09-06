package com.agilityhub.core.identity.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.identity.api.IdentityRequests.*;
import static com.agilityhub.core.identity.api.IdentityResponses.*;

@RestController
public class AccountAdministrationController {
    @PostMapping("/api/v1/members/{id}/impersonation-token")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create a non-refreshable member impersonation token",
            description = "ADMIN of the same club, never an impersonated token. R-01-09. Target lookup and grant issuance are E1-T04.",
            responses = {@ApiResponse(responseCode = "201", description = "Impersonation JWT and UTC expiry"),
                    @ApiResponse(responseCode = "403", description = "IMPERSONATION_DENIED"),
                    @ApiResponse(responseCode = "404", description = "NOT_FOUND within the current club")})
    public ImpersonationTokenResponse impersonation(@PathVariable String id, @Valid @RequestBody ImpersonationRequest request,
            Authentication authentication) {
        var jwt = ((JwtAuthenticationToken) authentication).getToken();
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp")) || authentication.getAuthorities().stream()
                .noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.IMPERSONATION_DENIED);
        }
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/platform/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("principal.claims['imp'] != true and (hasRole('AGILITYHUB_ADMIN') or "
            + "(hasAuthority('SCOPE_accounts:write') and principal.audience.contains('learn')))")
    @Operation(summary = "Provision a global account",
            description = "R-01-12. Authenticated learn client with accounts:write scope, or AGILITYHUB_ADMIN (S17). Idempotent.",
            responses = {@ApiResponse(responseCode = "201", description = "Public account including its ID for the Learn mapping"),
                    @ApiResponse(responseCode = "409", description = "EMAIL_EXISTS")})
    public AccountSummary createAccount(@Valid @RequestBody PlatformAccountRequest request) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/accounts/{id}/password")
    @PreAuthorize("principal.claims['imp'] != true and (hasRole('AGILITYHUB_ADMIN') or "
            + "(hasAuthority('SCOPE_accounts:write') and principal.audience.contains('learn')))")
    @Operation(summary = "Synchronize an existing Learn password hash",
            description = "R-01-12. Authenticated learn client with accounts:write scope, or AGILITYHUB_ADMIN (S17). Idempotent; never returns the hash.",
            responses = @ApiResponse(responseCode = "200", description = "Password synchronized", content = @Content))
    public ResponseEntity<Void> accountPassword(@PathVariable String id, @Valid @RequestBody AccountPasswordRequest request) {
        throw new UnsupportedOperationException();
    }
}
