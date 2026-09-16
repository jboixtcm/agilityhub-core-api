package com.agilityhub.core.clubs.activities.api;

import com.agilityhub.core.clubs.activities.domain.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static com.agilityhub.core.clubs.activities.api.ActivityContracts.ActivityVisibility;

public final class ActivityRequests {
    private ActivityRequests() { }
    public record ActivityCreateRequest(@NotEmpty Map<String, @NotBlank @Size(max = 80) String> title, @NotNull ActivityType type) { }
    public record ActivityLocationRequest(@NotNull Boolean atClub, @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 80) String name,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 200) String address,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String url) { }
    public record ActivityPatchRequest(@Schema(requiredMode = NOT_REQUIRED) Map<String, @NotBlank @Size(max = 80) String> title,
            @Schema(requiredMode = NOT_REQUIRED) ActivityType type,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Map<String, @Size(max = 30) String> typeLabel,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Map<String, @Size(max = 160) String> shortDescription,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Map<String, @Size(max = 20000) String> longDescription,
            @Schema(requiredMode = NOT_REQUIRED) @Valid ActivityLocationRequest location,
            @Schema(requiredMode = NOT_REQUIRED) List<String> ringIds, @Schema(requiredMode = NOT_REQUIRED) LocalDate date,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Pattern(regexp = "\\d{2}:\\d{2}") String startTime,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Pattern(regexp = "\\d{2}:\\d{2}") String endTime,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ActivityContracts.ActivityRingBlockWindow ringBlockWindow,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate registrationFrom, @Schema(requiredMode = NOT_REQUIRED) LocalDate registrationTo,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Positive Integer minPlaces,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Positive Integer maxPlaces,
            @Schema(requiredMode = NOT_REQUIRED) List<String> levelIds, @Schema(requiredMode = NOT_REQUIRED) Boolean waitlistEnabled,
            @Schema(requiredMode = NOT_REQUIRED) ActivityVisibility visibility,
            @Schema(requiredMode = NOT_REQUIRED, description = "Always empty in R1") @Size(max = 0) List<Void> priceTiers,
            @Schema(requiredMode = NOT_REQUIRED) @Size(min = 3, max = 80) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String slug,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 1000) String internalNotes,
            @Schema(requiredMode = NOT_REQUIRED) Boolean cancelBookings, @Schema(requiredMode = NOT_REQUIRED) Boolean cancelClasses,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 500) String adminText, @NotNull @PositiveOrZero Long version) { }
    public record ActivityImageRequest(@NotBlank String fileKey, @NotBlank @Size(max = 80) String name) { }
    public record ActivityDocumentRequest(@NotBlank String fileKey, @NotBlank @Size(max = 80) String name) { }
    public record PublicationRequest(@Schema(requiredMode = NOT_REQUIRED) Boolean notifyEmail,
            @Schema(requiredMode = NOT_REQUIRED) Boolean cancelBookings, @Schema(requiredMode = NOT_REQUIRED) Boolean cancelClasses,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 500) String adminText) { }
    public record ActivityCancellationRequest(@NotNull ActivityCancellationReason reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 500) String adminText) { }
    public record ActivityRegistrationRequest(@NotBlank String activityId,
            @Schema(requiredMode = NOT_REQUIRED, description = "Requires WAITLIST") Boolean joinWaitlist) { }
    public record RegistrationCancellationRequest(@Schema(requiredMode = NOT_REQUIRED, description = "Required when impersonating outside the cancellation window") @Size(max = 500) String reason) { }
}
