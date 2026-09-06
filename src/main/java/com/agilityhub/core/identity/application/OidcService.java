package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TenantHostResolver;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class OidcService {
    public static final String COOKIE = "__Host-agilityhub-id";
    private final RegisteredClientRepository clients;
    private final OidcStateRepository state;
    private final IdentityService identities;
    private final TokenService tokens;
    private final IdentityTransactions transactions;
    private final AccountMembershipRepository memberships;
    private final AccountSessionRepository accountSessions;
    private final ClubConfigService clubs;
    private final TenantHostResolver hosts;
    private final JwtEncoder encoder;
    private final SigningKeys keys;
    private final Clock clock;
    private final String issuer;
    private final String loginUrl;
    private final AuthSettings settings;
    public OidcService(RegisteredClientRepository clients, OidcStateRepository state, IdentityService identities,
                       TokenService tokens, IdentityTransactions transactions, AccountMembershipRepository memberships, AccountSessionRepository accountSessions,
                       ClubConfigService clubs, TenantHostResolver hosts, JwtEncoder encoder, SigningKeys keys, Clock clock,
                       AuthSettings settings, @Value("${identity.issuer}") String issuer, @Value("${core.oidc.login-url}") String loginUrl) {
        this.clients = clients; this.state = state; this.identities = identities; this.tokens = tokens;
        this.accountSessions = accountSessions; this.transactions = transactions; this.memberships = memberships; this.clubs = clubs; this.hosts = hosts;
        this.encoder = encoder; this.keys = keys; this.clock = clock; this.issuer = issuer; this.loginUrl = loginUrl; this.settings = settings;
        if (!origin(issuer).equals(origin(loginUrl)) || !"https".equals(URI.create(loginUrl).getScheme())) {
            throw new IllegalArgumentException("OIDC login page must share the HTTPS issuer origin");
        }
    }
    public String issuer() { return issuer; }
    public void initialize() { state.ensureIndexes(); }
    public RegisteredClient authenticateClient(String id, String secret) {
        var client = clients.findByClientId(id);
        if (client == null) { throw new ApiException(ErrorCode.INVALID_CREDENTIALS); }
        boolean publicClient = client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE);
        if (publicClient ? secret != null : secret == null || !new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches(secret, client.getClientSecret())) { throw new ApiException(ErrorCode.INVALID_CREDENTIALS); }
        return client;
    }
    public Set<String> scopes(RegisteredClient client, String value) {
        Set<String> scopes = value == null || value.isBlank() ? Set.of() : new HashSet<>(Arrays.asList(value.split(" +")));
        if (!client.getScopes().containsAll(scopes)) { throw invalid("invalid_scope"); }
        return Set.copyOf(scopes);
    }
    public OidcState.Request request(Map<String, String> params) {
        if (params.get("client_id") == null || params.get("client_id").isBlank()) { throw invalid("invalid_request"); }
        var client = clients.findByClientId(params.get("client_id"));
        if (client == null || !client.getAuthorizationGrantTypes().contains(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                || !"code".equals(params.get("response_type")) || !client.getRedirectUris().contains(params.get("redirect_uri"))) {
            throw invalid("invalid_request");
        }
        Set<String> scopes = scopes(client, params.get("scope"));
        if (!scopes.contains("openid")) { throw invalid("invalid_scope"); }
        String challenge = params.get("code_challenge");
        if (challenge != null || client.getClientSettings().isRequireProofKey()) {
            if (challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}") || !"S256".equals(params.get("code_challenge_method"))) {
                throw invalid("invalid_request");
            }
        } else if (params.containsKey("code_challenge_method")) { throw invalid("invalid_request"); }
        String prompt = params.getOrDefault("prompt", "");
        Set<String> prompts = prompt.isBlank() ? Set.of() : new HashSet<>(Arrays.asList(prompt.split(" +")));
        if (!Set.of("none", "login", "consent", "select_account").containsAll(prompts)
                || prompts.contains("none") && prompts.size() != 1) { throw invalid("invalid_request"); }
        Long maxAge = null;
        if (params.containsKey("max_age")) {
            try { maxAge = Long.valueOf(params.get("max_age")); if (maxAge < 0) { throw new NumberFormatException(); } }
            catch (NumberFormatException invalid) { throw invalid("invalid_request"); }
        }
        String clubId = null;
        if (Set.of("clubs-app", "clubs-admin").contains(client.getClientId())) {
            clubId = hosts.resolve(URI.create(params.get("redirect_uri")).getHost()).orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_HOST));
        }
        return new OidcState.Request(client.getClientId(), params.get("redirect_uri"), scopes, params.get("state"), challenge,
                params.get("nonce"), prompt, params.get("login_hint"), params.get("ui_locales"), maxAge, clubId);
    }
    public Redirect authorize(OidcState.Request request, String cookie) {
        var browser = browser(cookie);
        boolean force = request.prompt().contains("login") || request.prompt().contains("select_account")
                || browser != null && request.maxAge() != null && clock.instant().getEpochSecond() - browser.authTime().getEpochSecond() >= request.maxAge();
        if (browser != null && !force) {
            return transactions.run(() -> new Redirect(issueCode(request, browser), null));
        }
        if ("none".equals(request.prompt())) { return new Redirect(callback(request, "error", "login_required"), null); }
        String browserValue = TokenService.opaque();
        String flow = TokenService.opaque();
        state.insert(new OidcState.Flow(TokenService.digest(flow), TokenService.digest(browserValue), request,
                clock.instant(), clock.instant().plusSeconds(300)));
        var uri = UriComponentsBuilder.fromUriString(loginUrl).queryParam("flow", flow).queryParam("client_id", request.clientId());
        if (request.loginHint() != null) { uri.queryParam("login_hint", request.loginHint()); }
        if (request.uiLocales() != null) { uri.queryParam("ui_locales", request.uiLocales()); }
        if (!request.prompt().isBlank()) { uri.queryParam("prompt", request.prompt()); }
        return new Redirect(uri.build().encode().toUriString(), browserValue);
    }
    /** The unpredictable flow and host-only cookie bind the login UI's bearer POST against login CSRF. */
    public Redirect complete(String flow, String cookie, Jwt jwt, String origin) {
        if (cookie == null || flow == null || !origin(issuer).equals(origin) || Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"))
                || jwt.hasClaim("clubId") || !jwt.getAudience().equals(List.of("id-web"))) { throw new ApiException(ErrorCode.FORBIDDEN); }
        return transactions.run(() -> {
            var pending = state.flow(TokenService.digest(flow), TokenService.digest(cookie), clock.instant());
            if (pending == null) { throw invalid("invalid_request"); }
            var identity = identities.current(jwt.getSubject());
            var family = tokens.requireFamily(jwt.getSubject(), "id-web", jwt.getClaimAsString("sid"), identity.account().familyVersion());
            if (family.authTime().getEpochSecond() < pending.createdAt().getEpochSecond()) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
            if (!state.consumeFlow(pending.id(), pending.browserHash(), clock.instant())) { throw invalid("invalid_request"); }
            String value = TokenService.opaque();
            var browser = new OidcState.Browser(TokenService.digest(value), jwt.getSubject(), family.familyId(), family.authTime(), family.expiresAt());
            state.insert(browser);
            state.deleteBrowser(TokenService.digest(cookie));
            return new Redirect(issueCode(pending.request(), browser), value);
        });
    }
    private OidcState.Browser browser(String cookie) {
        if (cookie == null) { return null; }
        var browser = state.findById(TokenService.digest(cookie)).orElse(null);
        if (browser == null || !browser.expiresAt().isAfter(clock.instant())) { return null; }
        try {
            var identity = identities.current(browser.accountId());
            tokens.requireFamily(browser.accountId(), "id-web", browser.familyId(), identity.account().familyVersion());
            return browser;
        } catch (ApiException revoked) { return null; }
    }
    private String issueCode(OidcState.Request request, OidcState.Browser browser) {
        try (var scope = scope(request.clubId())) { identities.current(browser.accountId()); }
        String code = TokenService.opaque();
        state.insert(new OidcState.Code(TokenService.digest(code), request, browser.accountId(), browser.id(), browser.familyId(),
                browser.authTime(), clock.instant().plusSeconds(60)));
        return callback(request, "code", code);
    }
    public TokenService.Tokens exchange(String value, String clientId, String redirectUri, String verifier, String userAgent) {
        return transactions.run(() -> {
            var code = state.code(TokenService.digest(value), clientId, clock.instant());
            if (code == null || !code.request().redirectUri().equals(redirectUri)) { throw invalid("invalid_grant"); }
            if (code.request().challenge() != null && (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")
                    || !java.security.MessageDigest.isEqual(code.request().challenge().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    TokenService.digest(verifier).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))) { throw invalid("invalid_grant"); }
            String tenant = TenantContext.current();
            if (tenant != null && !tenant.equals(code.request().clubId())) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
            var account = identities.current(code.accountId()).account();
            boolean live = accountSessions.active(code.accountId(), account.familyVersion(), clock.instant()).stream()
                    .anyMatch(family -> family.clientId().equals("id-web") && family.familyId().equals(code.familyId()));
            if (!live || state.findById(code.browserHash()).isEmpty()) { throw invalid("invalid_grant"); }
            try (var scope = scope(code.request().clubId())) {
                if (!state.consumeCode(code.id(), clock.instant())) { throw invalid("invalid_grant"); }
                return tokens.authorizationCode(identities.current(code.accountId()), clientId, userAgent, code.request().scopes(), code.request().nonce(), code.authTime());
            }
        });
    }
    public TokenService.Tokens refresh(String value, String clientId, Set<String> scopes) {
        if (TenantContext.current() == null) {
            var bound = accountSessions.oidcRefresh(TokenService.digest(value), clientId).orElse(null);
            if (bound != null && bound.clubId() != null) {
                try (var scope = TenantContext.open(bound.clubId())) { return tokens.refresh(value, clientId, scopes); }
            }
        }
        return tokens.refresh(value, clientId, scopes);
    }
    public Map<String, Object> claims(String accountId, Set<String> scopes) {
        var account = identities.current(accountId).account();
        Map<String, Object> result = new LinkedHashMap<>(); result.put("sub", account.id());
        if (scopes.contains("email")) { result.put("email", account.email()); result.put("email_verified", account.emailVerifiedAt() != null); }
        if (scopes.contains("profile")) { result.put("name", account.name()); result.put("locale", account.locale()); }
        if (scopes.contains("memberships")) {
            result.put("memberships", memberships.activeForAccount(account.id()).stream().map(membership -> Map.of(
                    "clubId", membership.clubId(), "clubName", clubs.get(membership.clubId()).club().name(),
                    "roles", membership.roles().stream().map(Enum::name).sorted().toList())).toList());
        }
        return result;
    }
    public String idToken(TokenService.Tokens issued) {
        var access = issued.access();
        var builder = JwtClaimsSet.builder().issuer(issuer).subject(access.getSubject()).audience(access.getAudience())
                .issuedAt(clock.instant()).expiresAt(clock.instant().plus(TokenService.ACCESS_TTL))
                .claim("auth_time", issued.authTime().getEpochSecond()).claim("azp", access.getAudience().getFirst()).claim("token_use", "id")
                .claim("sid", access.getClaimAsString("sid"));
        if (issued.nonce() != null) { builder.claim("nonce", issued.nonce()); }
        var hash = Base64.getUrlDecoder().decode(TokenService.digest(access.getTokenValue()));
        builder.claim("at_hash", Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, hash.length / 2)));
        try (var scope = scope(access.getClaimAsString("clubId"))) {
            claims(access.getSubject(), issued.scopes()).forEach(builder::claim);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), builder.build())).getTokenValue();
    }
    public String logout(String hint, String redirectUri, String stateValue, String cookie) {
        try {
            var jwt = com.nimbusds.jwt.SignedJWT.parse(hint);
            var key = keys.publicKeys().getKeyByKeyId(jwt.getHeader().getKeyID());
            if (!(key instanceof com.nimbusds.jose.jwk.RSAKey rsa) || !com.nimbusds.jose.JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
                    || !jwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(rsa))) { throw invalid("invalid_request"); }
            var claims = jwt.getJWTClaimsSet();
            var audience = claims.getAudience();
            if (!issuer.equals(claims.getIssuer()) || !"id".equals(claims.getStringClaim("token_use")) || audience.size() != 1) { throw invalid("invalid_request"); }
            var client = clients.findByClientId(audience.getFirst());
            if (client == null || !client.getPostLogoutRedirectUris().contains(redirectUri)) { throw invalid("invalid_request"); }
            var browser = browser(cookie);
            if (browser != null && !browser.accountId().equals(claims.getSubject())) { throw new ApiException(ErrorCode.FORBIDDEN); }
            if (cookie != null) { state.deleteBrowser(TokenService.digest(cookie)); }
            var uri = UriComponentsBuilder.fromUriString(redirectUri);
            if (stateValue != null) { uri.queryParam("state", stateValue); }
            return uri.build().encode().toUriString();
        } catch (java.text.ParseException | com.nimbusds.jose.JOSEException failure) { throw invalid("invalid_request"); }
    }
    public long sessionSeconds() { return Duration.ofDays(settings.integer("auth.sessionDays")).toSeconds(); }
    private static String callback(OidcState.Request request, String name, String value) {
        var uri = UriComponentsBuilder.fromUriString(request.redirectUri()).queryParam(name, value);
        if (request.state() != null) { uri.queryParam("state", request.state()); }
        return uri.build().encode().toUriString();
    }
    private static TenantContext.Scope scope(String clubId) {
        if (clubId != null) { return TenantContext.open(clubId); }
        if (TenantContext.current() != null) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
        return () -> { };
    }
    private static String origin(String url) { var uri = URI.create(url); return uri.getScheme() + "://" + uri.getRawAuthority(); }
    public static ApiException invalid(String reason) { return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("oauth2Error", reason)); }
    public record Redirect(String url, String cookie) { @Override public String toString() { return "OidcRedirect[redacted]"; } }
}
