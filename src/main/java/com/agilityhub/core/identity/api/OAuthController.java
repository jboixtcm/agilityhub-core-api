package com.agilityhub.core.identity.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.identity.api.IdentityRequests.*;
import static com.agilityhub.core.identity.api.IdentityResponses.*;

@RestController
public class OAuthController {
    private final com.agilityhub.core.identity.application.TokenService tokens;
    private final com.agilityhub.core.identity.application.ImpersonationService impersonations;
    public OAuthController(com.agilityhub.core.identity.application.TokenService tokens, com.agilityhub.core.identity.application.ImpersonationService impersonations) {
        this.tokens = tokens; this.impersonations = impersonations;
    }
    @PostMapping(value = "/oauth2/token", consumes = "application/x-www-form-urlencoded")
    @SecurityRequirements
    @Operation(operationId = "token", summary = "Issue tokens using an OAuth2 or AgilityHub grant",
            description = "ANON with client authentication. Club context comes from the host, never request data. "
                    + "Password and magic-link grants issue sessions; refresh tokens rotate on every use. "
                    + "Handoff codes issue a destination session once within 60 seconds. Authorization-code grants and confidential clients follow in E1-T05. "
                    + "client_secret is required for confidential clients; code_verifier for public authorization-code clients.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = TokenRequest.class))),
            extensions = @Extension(name = "x-grants", properties = {
                    @ExtensionProperty(name = "password", value = "{\"required\":[\"client_id\",\"username\",\"password\"],\"optional\":[\"client_secret\",\"scope\"]}", parseValue = true),
                    @ExtensionProperty(name = "urn:agilityhub:grant:magic-link", value = "{\"required\":[\"client_id\",\"token\"],\"optional\":[\"client_secret\",\"scope\"]}", parseValue = true),
                    @ExtensionProperty(name = "urn:agilityhub:grant:handoff", value = "{\"required\":[\"client_id\",\"token\"],\"optional\":[\"client_secret\",\"scope\"]}", parseValue = true),
                    @ExtensionProperty(name = "authorization_code", value = "{\"required\":[\"client_id\",\"code\",\"redirect_uri\"],\"optional\":[\"client_secret\",\"code_verifier\",\"scope\"]}", parseValue = true),
                    @ExtensionProperty(name = "refresh_token", value = "{\"required\":[\"client_id\",\"refresh_token\"],\"optional\":[\"client_secret\",\"scope\"]}", parseValue = true)}),
            responses = {
                    @ApiResponse(responseCode = "200", description = "TokenResponse; refresh_token and id_token depend on grant and scope"),
                    @ApiResponse(responseCode = "400", description = "Invalid grant: MAGIC_LINK_INVALID, HANDOFF_INVALID, REFRESH_EXPIRED, REFRESH_REUSED; malformed form: VALIDATION_ERROR"),
                    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS"),
                    @ApiResponse(responseCode = "403", description = "NO_MEMBERSHIP, MEMBERSHIP_SUSPENDED, ACCOUNT_BLOCKED"),
                    @ApiResponse(responseCode = "429", description = "LOGIN_LOCKED or RATE_LIMITED; Retry-After in seconds")})
    public TokenResponse token(@Parameter(hidden = true) @ModelAttribute TokenRequest request) {
        throw new UnsupportedOperationException();
    }

    @GetMapping("/.well-known/openid-configuration")
    @SecurityRequirements
    @Operation(summary = "Discover the global OpenID provider", description = "ANON. R-01-11; implemented in E1-T05.",
            responses = @ApiResponse(responseCode = "200", description = "OIDC discovery metadata"))
    public OpenIdConfiguration discovery() { throw new UnsupportedOperationException(); }

    // Security filters serve these routes; MVC signatures provide the same typed public contract.
    @GetMapping("/.well-known/jwks.json")
    @SecurityRequirements
    @Operation(operationId = "wellKnownJwks", summary = "Get public JWT verification keys",
            description = "ANON, global. RSA public keys only; no private key material.",
            responses = @ApiResponse(responseCode = "200", description = "Public JWK set"))
    public JwkSet wellKnownJwks() { throw new UnsupportedOperationException(); }

    @GetMapping("/oauth2/jwks")
    @SecurityRequirements
    @Operation(operationId = "jwks", summary = "Get public JWT verification keys",
            description = "ANON, global. Compatibility alias for /.well-known/jwks.json.",
            responses = @ApiResponse(responseCode = "200", description = "Public JWK set"))
    public JwkSet jwks() { throw new UnsupportedOperationException(); }

    @GetMapping("/oauth2/authorize")
    @SecurityRequirements
    @Operation(summary = "Start authorization code flow with PKCE", description = "ANON. R-01-11; E1-T05. Public clients require S256 PKCE.",
            responses = {@ApiResponse(responseCode = "302", description = "Redirect to apps/id login or registered redirect_uri with code", content = @Content),
                    @ApiResponse(responseCode = "400", description = "Invalid request (VALIDATION_ERROR in the shared error envelope)")})
    public ResponseEntity<Void> authorize(
            @RequestParam @Parameter(schema = @Schema(allowableValues = "code")) String response_type,
            @RequestParam String client_id, @RequestParam String redirect_uri,
            @RequestParam String scope, @RequestParam String state,
            @RequestParam String code_challenge,
            @RequestParam @Parameter(schema = @Schema(allowableValues = "S256")) String code_challenge_method,
            @RequestParam(required = false) String login_hint, @RequestParam(required = false) String ui_locales,
            @RequestParam(required = false) String prompt) { throw new UnsupportedOperationException(); }

    @PostMapping("/oauth2/revoke")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Revoke a refresh session or impersonation grant",
            description = "Any valid account token. R-01-10. Idempotent; JSON body as specified in S01 §6.",
            responses = @ApiResponse(responseCode = "200", description = "Revoked (also when already revoked)", content = @Content))
    public ResponseEntity<Void> revoke(@jakarta.validation.Valid @RequestBody RevokeRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp")) && !jwt.getTokenValue().equals(request.token())) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.IMPERSONATION_DENIED);
        }
        if (!impersonations.revoke(jwt.getSubject(), request.token())) { tokens.revoke(jwt.getSubject(), request.token()); }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/oauth2/userinfo")
    @PreAuthorize("isAuthenticated() and hasAuthority('SCOPE_openid')")
    @Operation(summary = "Get account claims permitted by the token scopes",
            description = "Account token with openid scope; profile/email/memberships control the corresponding claims. R-01-11.",
            responses = @ApiResponse(responseCode = "200", description = "Scoped OIDC user claims"))
    public UserInfo userinfo() { throw new UnsupportedOperationException(); }

    @GetMapping("/connect/logout")
    @SecurityRequirements
    @Operation(summary = "End the OpenID provider session", description = "RP-initiated logout; validates the ID token hint and registered post-logout URI. R-01-11.",
            responses = @ApiResponse(responseCode = "302", description = "Redirect to registered post_logout_redirect_uri", content = @Content))
    public ResponseEntity<Void> logout(@RequestParam String id_token_hint, @RequestParam String post_logout_redirect_uri) {
        throw new UnsupportedOperationException();
    }
}
