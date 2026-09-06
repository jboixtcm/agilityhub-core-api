package com.agilityhub.core.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Public input contracts from S01 §6; business validation is implemented by the E1 services. */
public final class IdentityRequests {
    private IdentityRequests() { }

    public record TokenRequest(
            @NotBlank @Schema(allowableValues = {"password", "urn:agilityhub:grant:magic-link",
                    "urn:agilityhub:grant:handoff", "authorization_code", "refresh_token"}) String grant_type,
            @NotBlank String client_id,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String client_secret,
            String code_verifier, @Schema(format = "email") String username,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password,
            @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String token,
            @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String refresh_token,
            String code, @Schema(format = "uri") String redirect_uri, String scope) { }

    public enum MagicLinkPurpose { LOGIN, RESET }
    public record MagicLinkRequest(@NotBlank @Email String email, @NotNull MagicLinkPurpose purpose,
            @NotBlank @JsonProperty("client_id") String clientId,
            @JsonProperty("redirect_uri") @Schema(format = "uri") String redirectUri) { }
    public record RevokeRequest(@NotBlank @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String token) { }
    public record HandoffRequest(@NotBlank String targetClientId) { }
    public record AccountPatchRequest(@Schema(allowableValues = {"ca", "es", "en"}) String locale, String name) { }
    public record PasswordRequest(
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String current,
            @NotBlank @JsonProperty("new") @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String newPassword,
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String repeat) { }
    public record ProfileRequest(@NotNull IdentityResponses.Profile activeProfile, @NotNull Boolean remember) { }
    public record ImpersonationRequest(String reason) { }
    public record PlatformAccountRequest(@NotBlank @Email String email, @NotBlank String name,
            @NotBlank @Schema(allowableValues = {"ca", "es", "en"}) String locale,
            @Schema(description = "Existing Learn bcrypt hash, preserved on import", accessMode = Schema.AccessMode.WRITE_ONLY)
            String passwordHash) { }
    public record AccountPasswordRequest(@NotBlank @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String passwordHash) { }
    public record OnboardingRequest(String name, @Schema(allowableValues = {"ca", "es", "en"}) String locale,
            @Schema(description = "Requested only when configured by the club") String phone,
            @NotNull Boolean privacyAccepted, @NotBlank String privacyPolicyVersion, Boolean imageConsent) { }
}
