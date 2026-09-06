package com.agilityhub.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Set;
import static com.agilityhub.core.identity.api.IdentityResponses.*;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Schema(name = "Me", description = "R-01-15 app bootstrap. Membership is absent without club context; features are branding.modules.")
public record MeResponse(@Schema(requiredMode = REQUIRED) AccountSummary account,
        @JsonInclude(JsonInclude.Include.NON_NULL) MembershipSummary membership,
        @JsonInclude(JsonInclude.Include.NON_NULL) Impersonation impersonation,
        @Schema(requiredMode = REQUIRED) List<String> features) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MembershipSummary(@Schema(requiredMode = REQUIRED, format = "uuid") String clubId,
            @Schema(requiredMode = REQUIRED) Set<Profile> roles, Profile activeProfile,
            @Schema(requiredMode = REQUIRED) List<Profile> profiles,
            @Schema(format = "uuid") String memberId, @Schema(format = "uuid") String instructorId,
            Profile defaultProfile, @Schema(requiredMode = REQUIRED) boolean rememberProfile) { }
    public record Impersonation(@Schema(requiredMode = REQUIRED) String actorName) { }
}
