package com.agilityhub.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static com.agilityhub.core.identity.api.IdentityResponses.*;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

@Schema(name = "Me", description = "R-01-15 app bootstrap. Membership is absent without club context; features are branding.modules.")
public record MeResponse(@Schema(requiredMode = REQUIRED) MeAccount account,
        @Schema(requiredMode = NOT_REQUIRED) @JsonInclude(JsonInclude.Include.NON_NULL) MembershipSummary membership,
        @Schema(requiredMode = NOT_REQUIRED) @JsonInclude(JsonInclude.Include.NON_NULL) Impersonation impersonation,
        @Schema(requiredMode = REQUIRED) List<String> features) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MeAccount(@Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED, format = "email") String email,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED, allowableValues = {"ca", "es", "en", "fr", "de", "no", "pt"}) String locale,
            @Schema(requiredMode = REQUIRED) Set<PlatformRole> platformRoles,
            @Schema(requiredMode = REQUIRED) boolean hasPassword, @Schema(requiredMode = NOT_REQUIRED) Instant emailVerifiedAt,
            @Schema(requiredMode = REQUIRED) boolean onboardingPending) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MembershipSummary(@Schema(requiredMode = REQUIRED, format = "uuid") String clubId,
            @Schema(requiredMode = REQUIRED) Set<Profile> roles, @Schema(requiredMode = NOT_REQUIRED) Profile activeProfile,
            @Schema(requiredMode = REQUIRED) List<Profile> profiles,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String memberId, @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String instructorId,
            @Schema(requiredMode = NOT_REQUIRED) Profile defaultProfile, @Schema(requiredMode = REQUIRED) boolean rememberProfile,
            @Schema(requiredMode = NOT_REQUIRED, description = "Member.gender; absent when there is no member") Gender gender) { }
    public enum Gender { MALE, FEMALE, OTHER }
    public record Impersonation(@Schema(requiredMode = REQUIRED) String actorName) { }
}
