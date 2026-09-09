package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.IdentityService;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.identity.api.IdentityRequests.*;
import static com.agilityhub.core.identity.api.IdentityResponses.*;

@RestController
public class MeController {
    private final RefreshCookies refreshCookies;
    private final IdentityService identities;
    private final ClubConfigService clubs;
    private final com.agilityhub.core.identity.application.AccountService accounts;
    private final com.agilityhub.core.identity.application.PasswordService passwords;
    private final com.agilityhub.core.identity.application.TokenService tokens;
    private final com.agilityhub.core.identity.application.OnboardingService onboarding;
    private final com.agilityhub.core.identity.application.IdentityTransactions transactions;
    public MeController(IdentityService identities, ClubConfigService clubs, com.agilityhub.core.identity.application.AccountService accounts,
            com.agilityhub.core.identity.application.PasswordService passwords, com.agilityhub.core.identity.application.TokenService tokens,
            com.agilityhub.core.identity.application.OnboardingService onboarding, com.agilityhub.core.identity.application.IdentityTransactions transactions, RefreshCookies refreshCookies) {
        this.refreshCookies = refreshCookies;
        this.identities = identities; this.clubs = clubs; this.accounts = accounts; this.passwords = passwords; this.tokens = tokens;
        this.onboarding = onboarding; this.transactions = transactions;
    }
    @GetMapping("/api/v1/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the current account and active club context",
            description = "Any valid account token. R-01-15: only the current host/JWT membership, role profiles and enabled features. "
                    + "Includes global bootstrap and a member-only impersonation view with the actor name.",
            responses = @ApiResponse(responseCode = "200", description = "App bootstrap (Me)"))
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        var session = identities.current(jwt.getSubject());
        var account = session.account();
        var membership = session.membership();
        var publicAccount = new MeResponse.MeAccount(account.id(), account.email(), account.name(), account.locale(),
                account.platformRoles().stream().map(role -> PlatformRole.valueOf(role.name())).collect(Collectors.toSet()),
                account.passwordHash() != null, account.emailVerifiedAt(), account.onboardingPending());
        if (membership == null) { return new MeResponse(publicAccount, null, null, List.of()); }
        var current = com.agilityhub.core.shared.application.CurrentUser.current();
        if (current != null && current.impersonation() != null) {
            return new MeResponse(new MeResponse.MeAccount(account.id(), account.email(), account.name(), account.locale(), java.util.Set.of(),
                    account.passwordHash() != null, account.emailVerifiedAt(), account.onboardingPending()),
                    new MeResponse.MembershipSummary(membership.clubId(), java.util.Set.of(Profile.MEMBER), Profile.MEMBER, List.of(Profile.MEMBER),
                            membership.memberId(), null, null, false, null), new MeResponse.Impersonation(current.impersonation().actorName()),
                    clubs.get(TenantContext.require()).modules().stream().map(Enum::name).sorted().toList());
        }
        var profiles = membership.roles().stream().map(role -> Profile.valueOf(role.name())).sorted().toList();
        var defaultProfile = membership.defaultProfile() == null ? null : Profile.valueOf(membership.defaultProfile().name());
        String activeClaim = jwt.getClaimAsString("activeProfile");
        com.agilityhub.core.identity.domain.Role requested = activeClaim == null ? null : com.agilityhub.core.identity.domain.Role.valueOf(activeClaim);
        var activeProfile = Profile.valueOf(membership.activeProfile(requested).name());
        return new MeResponse(publicAccount,
                new MeResponse.MembershipSummary(membership.clubId(), java.util.Set.copyOf(profiles), activeProfile, profiles,
                        membership.memberId(), membership.instructorId(), defaultProfile, membership.rememberProfile(), null), null,
                clubs.get(TenantContext.require()).modules().stream().map(Enum::name).sorted().toList());
    }

    @PatchMapping("/api/v1/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update account language or display name", description = "Any valid account token. R-01-14.",
            responses = {@ApiResponse(responseCode = "200", description = "Updated app bootstrap (Me)"),
                    @ApiResponse(responseCode = "400", description = "LOCALE_NOT_SUPPORTED")})
    public MeResponse update(@Valid @RequestBody AccountPatchRequest request, @AuthenticationPrincipal Jwt jwt) {
        identities.current(jwt.getSubject());
        accounts.patch(jwt.getSubject(), request.locale(), request.name());
        return me(jwt);
    }

    @PutMapping("/api/v1/me/password")
    @PreAuthorize("isAuthenticated() and principal.claims['imp'] != true")
    @Operation(summary = "Set or change the current account password",
            description = "Account token, never impersonated. R-01-05: current is required only when a password already exists.",
            responses = {@ApiResponse(responseCode = "200", description = "Password changed", content = @Content),
                    @ApiResponse(responseCode = "400", description = "PASSWORD_TOO_SHORT, PASSWORD_MISMATCH, PASSWORD_COMPROMISED"),
                    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS for current password"),
                    @ApiResponse(responseCode = "403", description = "FORBIDDEN for impersonated accounts")})
    public ResponseEntity<Void> password(@Valid @RequestBody PasswordRequest request, @AuthenticationPrincipal Jwt jwt) {
        passwords.change(jwt.getSubject(), jwt.getClaimAsString("azp"), jwt.getClaimAsString("sid"), request.current(), request.newPassword(), request.repeat());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/api/v1/me/profile")
    @PreAuthorize("isAuthenticated() and principal.claims['imp'] != true")
    @Operation(summary = "Select an available club profile", description = "Account token with club context. R-01-07. Returns a fresh access token.",
            responses = {@ApiResponse(responseCode = "200", description = "New access token"),
                    @ApiResponse(responseCode = "422", description = "PROFILE_NOT_AVAILABLE")})
    public ProfileResponse profile(@Valid @RequestBody ProfileRequest request, @AuthenticationPrincipal Jwt jwt) {
        return new ProfileResponse(tokens.profile(jwt.getSubject(), jwt.getClaimAsString("azp"), jwt.getClaimAsString("sid"),
                com.agilityhub.core.identity.domain.Role.valueOf(request.activeProfile().name()), request.remember()).getTokenValue());
    }

    @GetMapping("/api/v1/me/sessions")
    @PreAuthorize("isAuthenticated() and principal.claims['imp'] != true")
    @Operation(summary = "List the current account's device sessions", description = "Any valid account token. R-01-06, R-01-10. Bounded device list, not a desktop paginated list.",
            responses = @ApiResponse(responseCode = "200", description = "Sessions without refresh tokens or token hashes"))
    public List<Session> sessions(@AuthenticationPrincipal Jwt jwt) {
        return tokens.sessions(jwt.getSubject()).stream().map(token -> new Session(token.familyId(), token.clientId(), token.clubId(),
                token.activeProfile() == null ? null : Profile.valueOf(token.activeProfile().name()), token.deviceLabel(),
                token.createdAt(), token.expiresAt(), token.lastUsedAt())).toList();
    }

    @DeleteMapping("/api/v1/me/sessions/{id}")
    @PreAuthorize("isAuthenticated() and principal.claims['imp'] != true")
    @Operation(summary = "Revoke one of the current account's sessions", description = "Any valid account token. R-01-10. The session must belong to this account.",
            responses = @ApiResponse(responseCode = "200", description = "Session revoked", content = @Content))
    public ResponseEntity<Void> deleteSession(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, jakarta.servlet.http.HttpServletRequest request) {
        tokens.revokeSession(jwt.getSubject(), id);
        var response = ResponseEntity.ok();
        if (id.equals(jwt.getClaimAsString("sid"))) { response.header("Set-Cookie", refreshCookies.clearHeader(request)); }
        return response.build();
    }

    @GetMapping("/api/v1/me/onboarding")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get required first-access profile and privacy steps", description = "Any valid account token. S01 §14, T-01-26. Platform consent first, then the current club policy.",
            responses = @ApiResponse(responseCode = "200", description = "Pending onboarding and current privacy policy"))
    public OnboardingState onboarding(@AuthenticationPrincipal Jwt jwt) { return onboardingState(onboarding.state(jwt.getSubject())); }

    @PutMapping("/api/v1/me/onboarding")
    @PreAuthorize("isAuthenticated() and principal.claims['imp'] != true")
    @Operation(summary = "Complete first-access privacy consent and optional profile fields",
            description = "Account token, never impersonated. S01 §14, T-01-26: consentAccepted must be true for the current consentVersion. "
                    + "Profile data may be deferred; imageConsent is optional.",
            responses = {@ApiResponse(responseCode = "200", description = "Updated onboarding state"),
                    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR if consent is not accepted; LOCALE_NOT_SUPPORTED"),
                    @ApiResponse(responseCode = "422", description = "CONSENT_VERSION_OUTDATED")})
    public OnboardingState completeOnboarding(@Valid @RequestBody OnboardingRequest request, @AuthenticationPrincipal Jwt jwt) {
        var fields = request.fields();
        return onboardingState(transactions.run(() -> onboarding.complete(jwt.getSubject(), request.consentAccepted(), request.consentVersion(),
                fields == null ? null : fields.name(), fields == null ? null : fields.locale(), fields == null ? null : fields.phone(), request.imageConsent())));
    }

    @PostMapping("/api/v1/me/onboarding/postpone")
    @PreAuthorize("isAuthenticated() and principal.claims['imp'] != true")
    @Operation(summary = "Postpone the current consent prompt",
            description = "Account token, never impersonated. S01 §6, §14: policy renewals may be postponed, bounded by legal.maxPostpones. "
                    + "First acceptance is mandatory. At zero returns the unchanged state without an error.",
            responses = @ApiResponse(responseCode = "200", description = "Updated onboarding state"))
    public OnboardingState postponeOnboarding(@AuthenticationPrincipal Jwt jwt) {
        return onboardingState(transactions.run(() -> onboarding.postpone(jwt.getSubject())));
    }
    private OnboardingState onboardingState(com.agilityhub.core.identity.application.OnboardingService.State state) {
        var consent = state.requiredConsent();
        return new OnboardingState(state.pending(), state.postponeRemaining(), consent == null ? null
                : new RequiredConsent(ConsentPolicy.valueOf(consent.policy().name()), consent.version(), consent.url()),
                state.fields().stream().map(field -> new OnboardingField(field.key(), field.value(), field.required())).toList());
    }
}
