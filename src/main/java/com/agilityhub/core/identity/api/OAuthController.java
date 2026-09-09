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
    private final com.agilityhub.core.identity.application.OidcService oidc;
    private final com.agilityhub.core.identity.application.SigningKeys keys;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final RefreshCookies refreshCookies;
    public OAuthController(com.agilityhub.core.identity.application.TokenService tokens, com.agilityhub.core.identity.application.ImpersonationService impersonations,
            com.agilityhub.core.identity.application.OidcService oidc, com.agilityhub.core.identity.application.SigningKeys keys, com.fasterxml.jackson.databind.ObjectMapper mapper, RefreshCookies refreshCookies) {
        this.refreshCookies = refreshCookies;
        this.oidc = oidc; this.keys = keys; this.mapper = mapper; this.tokens = tokens; this.impersonations = impersonations;
    }
    @PostMapping(value = "/oauth2/token", consumes = "application/x-www-form-urlencoded")
    @SecurityRequirements
    @Operation(operationId = "token", summary = "Issue tokens using an OAuth2 or AgilityHub grant",
            description = "ANON with client authentication. Club context comes from the host, never request data. "
                    + "Password and magic-link grants issue sessions; refresh tokens rotate on every use. "
                    + "Handoff codes issue a destination session once within 60 seconds. Authorization-code grants support S256 PKCE, scoped claims and confidential clients. "
                    + "COOKIE clients receive ah_refresh (HttpOnly, Secure, SameSite=Strict, host-only, Path=/oauth2/token); refresh_token is omitted from JSON. "
                    + "Cookie refresh requires explicit client_id and a same-host Origin or Referer. BODY clients retain JSON refresh tokens. "
                    + "client_secret is required for confidential clients; code_verifier for public authorization-code clients.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = TokenRequest.class))),
            extensions = @Extension(name = "x-grants", properties = {
                    @ExtensionProperty(name = "password", value = "{\"required\":[\"client_id\",\"username\",\"password\"],\"optional\":[\"client_secret\",\"scope\"]}", parseValue = true),
                    @ExtensionProperty(name = "urn:agilityhub:grant:magic-link", value = "{\"required\":[\"client_id\",\"token\"],\"optional\":[\"client_secret\",\"scope\"]}", parseValue = true),
                    @ExtensionProperty(name = "urn:agilityhub:grant:handoff", value = "{\"required\":[\"client_id\",\"token\"],\"optional\":[\"client_secret\",\"scope\"]}", parseValue = true),
                    @ExtensionProperty(name = "authorization_code", value = "{\"required\":[\"client_id\",\"code\",\"redirect_uri\"],\"optional\":[\"client_secret\",\"code_verifier\",\"scope\"]}", parseValue = true),
                    @ExtensionProperty(name = "refresh_token", value = "{\"required\":[\"client_id\"],\"optional\":[\"refresh_token\",\"client_secret\",\"scope\"],\"cookie\":\"ah_refresh for COOKIE clients; refresh_token required for BODY clients\"}", parseValue = true)}),
            responses = {
                    @ApiResponse(responseCode = "200", description = "TokenResponse; refresh_token only for BODY clients; id_token depends on grant and scope",
                            headers = @io.swagger.v3.oas.annotations.headers.Header(name = "Set-Cookie", description = "COOKIE clients: ah_refresh; HttpOnly; Secure; SameSite=Strict; Path=/oauth2/token; Max-Age=auth.sessionDays in seconds. Secure relaxed only for local HTTP.", schema = @Schema(type = "string"))),
                    @ApiResponse(responseCode = "400", description = "Invalid grant: MAGIC_LINK_INVALID, HANDOFF_INVALID, REFRESH_EXPIRED, REFRESH_REUSED; malformed form: VALIDATION_ERROR"),
                    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS"),
                    @ApiResponse(responseCode = "403", description = "NO_MEMBERSHIP, MEMBERSHIP_SUSPENDED, ACCOUNT_BLOCKED"),
                    @ApiResponse(responseCode = "429", description = "LOGIN_LOCKED or RATE_LIMITED; Retry-After in seconds")})
    public TokenResponse token(@Parameter(hidden = true) @ModelAttribute TokenRequest request) {
        throw new UnsupportedOperationException();
    }

    @GetMapping("/.well-known/openid-configuration")
    @SecurityRequirements
    @Operation(summary = "Discover the global OpenID provider", description = "ANON. Global OIDC provider metadata. R-01-11.",
            responses = @ApiResponse(responseCode = "200", description = "OIDC discovery metadata"))
    public ResponseEntity<OpenIdConfiguration> discovery() {
        String issuer = oidc.issuer();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofSeconds(60)).cachePublic())
                .body(new OpenIdConfiguration(issuer, issuer + "/oauth2/authorize", issuer + "/oauth2/token",
                issuer + "/oauth2/userinfo", issuer + "/.well-known/jwks.json", issuer + "/oauth2/revoke", issuer + "/connect/logout",
                java.util.List.of("openid", "profile", "email", "memberships", "offline_access", "accounts:write"),
                java.util.List.of("code"), java.util.List.of("authorization_code", "password", "refresh_token",
                    "urn:agilityhub:grant:magic-link", "urn:agilityhub:grant:handoff"),
                java.util.List.of("public"), java.util.List.of("RS256"), java.util.List.of("none", "client_secret_post", "client_secret_basic"), java.util.List.of("S256")));
    }

    // Security filters serve these routes; MVC signatures provide the same typed public contract.
    @GetMapping("/.well-known/jwks.json")
    @SecurityRequirements
    @Operation(operationId = "wellKnownJwks", summary = "Get public JWT verification keys",
            description = "ANON, global. RSA public keys only; no private key material.",
            responses = @ApiResponse(responseCode = "200", description = "Public JWK set"))
    public ResponseEntity<JwkSet> wellKnownJwks() {
        var publicKeys = keys.publicKeys().getKeys().stream().map(key -> {
            var rsa = (com.nimbusds.jose.jwk.RSAKey) key;
            return new PublicJwk("RSA", rsa.getKeyID(), "sig", "RS256", rsa.getModulus().toString(), rsa.getPublicExponent().toString());
        }).toList();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofSeconds(60)).cachePublic()).body(new JwkSet(publicKeys));
    }

    @GetMapping("/oauth2/jwks")
    @SecurityRequirements
    @Operation(operationId = "jwks", summary = "Get public JWT verification keys",
            description = "ANON, global. Compatibility alias for /.well-known/jwks.json.",
            responses = @ApiResponse(responseCode = "200", description = "Public JWK set"))
    public ResponseEntity<JwkSet> jwks() { return wellKnownJwks(); }

    @GetMapping("/oauth2/authorize")
    @SecurityRequirements
    @Operation(summary = "Start authorization code flow with PKCE", description = "ANON. R-01-11; E1-T05. Public clients require S256 PKCE.",
            responses = {@ApiResponse(responseCode = "302", description = "Redirect to apps/id login or registered redirect_uri with code", content = @Content),
                    @ApiResponse(responseCode = "400", description = "Invalid request (VALIDATION_ERROR in the shared error envelope)")})
    public ResponseEntity<Void> authorize(
            @RequestParam(required = false) @Parameter(schema = @Schema(allowableValues = "code")) String response_type,
            @RequestParam(required = false) String client_id, @RequestParam(required = false) String redirect_uri,
            @RequestParam(required = false) String scope, @RequestParam(required = false) String state,
            @RequestParam(required = false) String code_challenge,
            @RequestParam(required = false) @Parameter(schema = @Schema(allowableValues = "S256")) String code_challenge_method,
            @RequestParam(required = false) String login_hint, @RequestParam(required = false) String ui_locales,
            @RequestParam(required = false) String prompt, @RequestParam(required = false) String nonce,
            @RequestParam(required = false) Long max_age, jakarta.servlet.http.HttpServletRequest request) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values.length != 1 || values[0].length() > 2048) { throw com.agilityhub.core.identity.application.OidcService.invalid("invalid_request"); }
            params.put(key, values[0]);
        });
        var result = oidc.authorize(oidc.request(params), cookie(request));
        var response = ResponseEntity.status(302).location(java.net.URI.create(result.url())).cacheControl(org.springframework.http.CacheControl.noStore());
        if (result.cookie() != null) { response.header("Set-Cookie", sessionCookie(result.cookie(), oidc.sessionSeconds())); }
        return response.build();
    }

    public record OidcSessionRequest(@jakarta.validation.constraints.NotBlank String flow) { }
    public record OidcSessionResponse(String redirectUrl) { }
    @PostMapping("/oauth2/session")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Resume a browser authorization after apps/id login",
            description = "Global id-web bearer token, matching flow cookie and same-origin Origin required. "
                    + "The UI navigates to redirectUrl after this POST; no bearer token is put in a URL.")
    public ResponseEntity<OidcSessionResponse> session(@jakarta.validation.Valid @RequestBody OidcSessionRequest body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt,
            jakarta.servlet.http.HttpServletRequest request) {
        var result = oidc.complete(body.flow(), cookie(request), jwt, request.getHeader("Origin"));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .header("Set-Cookie", sessionCookie(result.cookie(), oidc.sessionSeconds())).body(new OidcSessionResponse(result.url()));
    }
    private static String cookie(jakarta.servlet.http.HttpServletRequest request) {
        if (request.getCookies() == null) { return null; }
        return java.util.Arrays.stream(request.getCookies()).filter(cookie -> cookie.getName().equals(com.agilityhub.core.identity.application.OidcService.COOKIE))
                .map(jakarta.servlet.http.Cookie::getValue).findFirst().orElse(null);
    }
    private static String sessionCookie(String value, long seconds) {
        return org.springframework.http.ResponseCookie.from(com.agilityhub.core.identity.application.OidcService.COOKIE, value)
                .secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(seconds).build().toString();
    }

    @PostMapping("/oauth2/revoke")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Revoke a refresh session or impersonation grant",
            description = "Any valid account token. R-01-10. Idempotent. JSON token is optional for COOKIE clients: {} revokes the bearer sid because the refresh cookie path excludes this route. Clears ah_refresh.",
            responses = @ApiResponse(responseCode = "200", description = "Revoked (also when already revoked)", content = @Content))
    public ResponseEntity<Void> revoke(@jakarta.validation.Valid @RequestBody RevokeRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt, jakarta.servlet.http.HttpServletRequest servletRequest) {
        if (request.token() == null && !Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"))
                && refreshCookies.cookieClient(jwt.getClaimAsString("azp"))) {
            tokens.revokeSession(jwt.getSubject(), jwt.getClaimAsString("sid"));
            return ResponseEntity.ok().header("Set-Cookie", refreshCookies.clearHeader(servletRequest)).build();
        }
        if (request.token() == null || request.token().isBlank()) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.VALIDATION_ERROR);
        }
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp")) && !jwt.getTokenValue().equals(request.token())) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.IMPERSONATION_DENIED);
        }
        if (!impersonations.revoke(jwt.getSubject(), request.token())) { tokens.revoke(jwt.getSubject(), request.token()); }
        return ResponseEntity.ok().header("Set-Cookie", refreshCookies.clearHeader(servletRequest)).build();
    }

    @GetMapping("/oauth2/userinfo")
    @PreAuthorize("isAuthenticated() and hasAuthority('SCOPE_openid')")
    @Operation(summary = "Get account claims permitted by the token scopes",
            description = "Account token with openid scope; profile/email/memberships control the corresponding claims. R-01-11.",
            responses = @ApiResponse(responseCode = "200", description = "Scoped OIDC user claims"))
    public ResponseEntity<UserInfo> userinfo(@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        var scopes = java.util.Set.copyOf(java.util.Arrays.asList(java.util.Objects.toString(jwt.getClaimAsString("scope"), "").split(" +")));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(mapper.convertValue(oidc.claims(jwt.getSubject(), scopes), UserInfo.class));
    }

    @GetMapping("/connect/logout")
    @SecurityRequirements
    @Operation(summary = "End the OpenID provider session", description = "RP-initiated logout; validates the ID token hint and registered post-logout URI. R-01-11.",
            responses = @ApiResponse(responseCode = "302", description = "Redirect to registered post_logout_redirect_uri", content = @Content))
    public ResponseEntity<Void> logout(@RequestParam String id_token_hint, @RequestParam String post_logout_redirect_uri,
            @RequestParam(required = false) String state, jakarta.servlet.http.HttpServletRequest request) {
        String destination = oidc.logout(id_token_hint, post_logout_redirect_uri, state, cookie(request));
        return ResponseEntity.status(302).location(java.net.URI.create(destination)).cacheControl(org.springframework.http.CacheControl.noStore())
                .header("Set-Cookie", sessionCookie("", 0), refreshCookies.clearHeader(request)).build();
    }
}
