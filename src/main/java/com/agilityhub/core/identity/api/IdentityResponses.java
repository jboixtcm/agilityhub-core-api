package com.agilityhub.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Output contracts intentionally contain no persistence entities or credential hashes. */
public final class IdentityResponses {
    private IdentityResponses() { }

    @Schema(name = "Profile", enumAsRef = true)
    public enum Profile { MEMBER, INSTRUCTOR, ADMIN }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenResponse(
            @Schema(requiredMode = REQUIRED) String access_token,
            @Schema(requiredMode = REQUIRED, allowableValues = "Bearer") String token_type,
            @Schema(requiredMode = REQUIRED, minimum = "1") long expires_in,
            String refresh_token, String id_token, @Schema(requiredMode = REQUIRED) String scope) { }
    public record ProfileResponse(@Schema(requiredMode = REQUIRED) String access_token) { }
    public record HandoffResponse(@Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED, format = "uri") String url) { }
    public record ImpersonationTokenResponse(@Schema(requiredMode = REQUIRED) String token,
            @Schema(requiredMode = REQUIRED) Instant expiresAt,
            @Schema(format = "uri", description = "S03 member-app launch URL") String launchUrl) { }

    public record AccountSummary(@Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED, format = "email") String email,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED, allowableValues = {"ca", "es", "en"}) String locale,
            @Schema(requiredMode = REQUIRED) Set<PlatformRole> platformRoles) { }
    public enum PlatformRole { AGILITYHUB_ADMIN }

    @Schema(name = "Session", description = "One device/session; id is an opaque revocation handle, never a token or hash")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Session(@Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String clientId,
            @Schema(format = "uuid") String clubId, Profile activeProfile,
            @Schema(requiredMode = REQUIRED) String deviceLabel,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) Instant expiresAt, Instant lastUsedAt) { }

    @Schema(description = "Pending onboarding, remaining postponements and current consent; S01 §14")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OnboardingState(@Schema(requiredMode = REQUIRED) boolean pending,
            @Schema(requiredMode = REQUIRED, minimum = "0") int postponeRemaining,
            @Schema(requiredMode = REQUIRED) RequiredConsent requiredConsent,
            @Schema(requiredMode = REQUIRED, description = "Keys and required flags from signup.onboardingFields")
            List<OnboardingField> fields) { }
    public enum ConsentPolicy { PLATFORM, CLUB }
    @Schema(types = {"object", "null"})
    public record RequiredConsent(@Schema(requiredMode = REQUIRED) ConsentPolicy policy,
            @Schema(requiredMode = REQUIRED) String version,
            @Schema(requiredMode = REQUIRED, format = "uri") String url) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OnboardingField(@Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED, types = {"string", "null"}) String value,
            @Schema(requiredMode = REQUIRED) boolean required) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserInfo(@Schema(requiredMode = REQUIRED) String sub, String email, Boolean email_verified,
            String name, String locale,
            @Schema(description = "Included only with the memberships scope") List<UserInfoMembership> memberships) { }
    public record UserInfoMembership(String clubId, String clubName, Set<Profile> roles) { }
    public record OpenIdConfiguration(String issuer, String authorization_endpoint, String token_endpoint,
            String userinfo_endpoint, String jwks_uri, String revocation_endpoint, String end_session_endpoint,
            List<String> scopes_supported, List<String> response_types_supported, List<String> grant_types_supported,
            List<String> subject_types_supported, List<String> id_token_signing_alg_values_supported,
            List<String> token_endpoint_auth_methods_supported, List<String> code_challenge_methods_supported) { }
    public record JwkSet(@Schema(requiredMode = REQUIRED) List<PublicJwk> keys) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublicJwk(@Schema(requiredMode = REQUIRED, allowableValues = "RSA") String kty,
            @Schema(requiredMode = REQUIRED) String kid, String use, String alg,
            @Schema(requiredMode = REQUIRED) String n, @Schema(requiredMode = REQUIRED) String e) { }
}
