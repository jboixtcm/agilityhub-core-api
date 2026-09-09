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
    private final com.agilityhub.core.identity.application.ImpersonationService impersonations;
    private final com.agilityhub.core.identity.application.PlatformRoleService roles;
    private final com.agilityhub.core.identity.application.IdentityTransactions transactions;
    public AccountAdministrationController(com.agilityhub.core.identity.application.ImpersonationService impersonations,
            com.agilityhub.core.identity.application.PlatformRoleService roles,
            com.agilityhub.core.identity.application.IdentityTransactions transactions) {
        this.impersonations = impersonations; this.roles = roles; this.transactions = transactions;
    }
    public record PlatformRoles(@jakarta.validation.constraints.NotNull
            java.util.Set<@jakarta.validation.constraints.NotNull PlatformRole> platformRoles) { }

    @GetMapping("/api/v1/platform/accounts/{id}/platform-roles")
    @PreAuthorize("principal.claims['imp'] != true and hasRole('AGILITYHUB_ADMIN')")
    @Operation(summary = "Get global account platform roles", description = "AGILITYHUB_ADMIN only; global, independent of club membership. R-17-09.",
            responses = {@ApiResponse(responseCode = "200", description = "Platform roles"),
                    @ApiResponse(responseCode = "403", description = "FORBIDDEN"), @ApiResponse(responseCode = "404", description = "NOT_FOUND")})
    public PlatformRoles platformRoles(@PathVariable String id, Authentication authentication) {
        return platformRoles(roles.get(authentication.getName(), id));
    }

    @PutMapping("/api/v1/platform/accounts/{id}/platform-roles")
    @PreAuthorize("principal.claims['imp'] != true and hasRole('AGILITYHUB_ADMIN')")
    @Operation(summary = "Replace global account platform roles", description = "AGILITYHUB_ADMIN only. R-17-09: preserves the last active platform admin; audited atomically, no domain event.",
            responses = {@ApiResponse(responseCode = "200", description = "Updated platform roles"),
                    @ApiResponse(responseCode = "403", description = "FORBIDDEN or ACCOUNT_BLOCKED"),
                    @ApiResponse(responseCode = "404", description = "NOT_FOUND"),
                    @ApiResponse(responseCode = "409", description = "LAST_PLATFORM_ADMIN")})
    public PlatformRoles platformRoles(@PathVariable String id, @Valid @RequestBody PlatformRoles request, Authentication authentication) {
        return platformRoles(transactions.run(() -> roles.replace(authentication.getName(), id,
                request.platformRoles().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()))));
    }
    private PlatformRoles platformRoles(java.util.Set<String> values) {
        return new PlatformRoles(values.stream().map(PlatformRole::valueOf).collect(java.util.stream.Collectors.toSet()));
    }
    @PostMapping("/api/v1/members/{id}/impersonation-token")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create a non-refreshable member impersonation token",
            description = "ADMIN of the same club, never an impersonated token. R-01-09. Access expires per auth.impersonationMinutes; no refresh token is issued.",
            responses = {@ApiResponse(responseCode = "201", description = "Impersonation JWT and UTC expiry"),
                    @ApiResponse(responseCode = "403", description = "IMPERSONATION_DENIED"),
                    @ApiResponse(responseCode = "404", description = "NOT_FOUND within the current club")})
    public ImpersonationTokenResponse impersonation(@PathVariable String id, @Valid @RequestBody ImpersonationRequest request,
            Authentication authentication) {
        var jwt = ((JwtAuthenticationToken) authentication).getToken();
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp")) || authentication.getAuthorities().stream()
                .noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))) {
            impersonations.deny(jwt.getSubject());
        }
        try {
            var issued = impersonations.create(jwt.getSubject(), id, request.reason());
            return new ImpersonationTokenResponse(issued.token().getTokenValue(), issued.expiresAt(), null);
        } catch (com.agilityhub.core.shared.domain.ApiException denied) {
            // Record only after the grant transaction rolls back.
            if (denied.code().httpStatus() == 403) { impersonations.rejected(jwt.getSubject()); }
            throw denied;
        }
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
