package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.domain.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import com.agilityhub.core.courses.domain.CourseParts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** S16 request bodies. Persistence and calculated response fields cannot be mass assigned. */
public final class CourseRequests {
    private CourseRequests() { }
    public record CourseRequest(@NotEmpty Map<String,@NotBlank String> name,
            @NotNull Discipline discipline,
            @NotNull CourseSource source,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) JsonNode normalizedJson,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer schemaVersion,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String imageFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String sourceFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String thumbnailFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String designerName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate designedOn,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String eventName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) AgilityHubLevel agilityhubLevel,
            @NotNull @Valid List<String> levelIds,
            @NotNull @Valid List<String> tags,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes,
            @NotNull Visibility visibility,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) SizeCategory sizeCategory) { }

    public record CoursePatchRequest(@Schema(requiredMode = NOT_REQUIRED) @Size(min = 1) Map<String,@NotBlank String> name,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Discipline discipline,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) CourseSource source,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) JsonNode normalizedJson,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer schemaVersion,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String imageFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String sourceFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String thumbnailFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String designerName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate designedOn,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String eventName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) AgilityHubLevel agilityhubLevel,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Valid List<String> levelIds,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Valid List<String> tags,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Visibility visibility,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) SizeCategory sizeCategory,
            @NotNull @PositiveOrZero Long version) { }

    public record CourseCopyRequest(@Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(min = 1) Map<String,@NotBlank String> name) { }

    @Schema(name = "CourseUploadUrlRequest")
    public record UploadUrlRequest(@NotNull UploadPurpose purpose,
            @NotBlank String contentType,
            @NotNull @PositiveOrZero Long size) { }

    public record RingGeometryRequest(@NotNull @DecimalMin("10") @DecimalMax("60") Double lengthM,
            @NotNull @DecimalMin("10") @DecimalMax("60") Double widthM,
            @NotNull @PositiveOrZero Double borderClearanceM,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Double orientationDeg,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Surface surface,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes,
            @NotNull @Valid List<@NotNull RingDoor> doors,
            @NotNull @Valid List<@NotNull RingNoGoZone> noGoZones,
            @NotNull @Valid List<@NotNull RingMarker> markers,
            @NotNull @PositiveOrZero Long version) { }

    public record CalibrationRequest(@NotNull CalibrationOutcome outcome,
            @NotNull @PositiveOrZero Double averageErrorCm,
            @NotNull @PositiveOrZero Double maxErrorCm,
            @NotNull @PositiveOrZero Integer markerCount,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String deviceModel,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String osVersion,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String appVersion,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String arProvider,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes,
            @NotNull JsonNode rawMetadata) { }

    public record PlacementRequest(@NotBlank String courseId,
            @NotNull @PositiveOrZero Long courseVersion,
            @NotBlank String ringId,
            @NotNull @Valid PlacementTransform transform,
            @NotNull PlacementMode placementMode,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @PositiveOrZero Double warningThresholdM,
            @NotNull @Valid List<@NotNull ResolvedObstacle> resolvedObstacles,
            @NotNull @Valid List<@NotNull PlacementWarning> warnings,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String activityId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String name,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Boolean force,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) PlacementStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) PlacementUse mode,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant scheduledAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Valid List<String> grades,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Valid List<DogSize> sizes,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String displayName) { }

    public record PlacementUpdateRequest(@NotBlank String courseId,
            @NotNull @PositiveOrZero Long courseVersion,
            @NotBlank String ringId,
            @NotNull @Valid PlacementTransform transform,
            @NotNull PlacementMode placementMode,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @PositiveOrZero Double warningThresholdM,
            @NotNull @Valid List<@NotNull ResolvedObstacle> resolvedObstacles,
            @NotNull @Valid List<@NotNull PlacementWarning> warnings,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String activityId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String name,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Boolean force,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) PlacementStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) PlacementUse mode,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant scheduledAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Valid List<String> grades,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Valid List<DogSize> sizes,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String displayName,
            @NotNull @PositiveOrZero Long version) { }

    public record BuildSheetRequest(@NotBlank String fileKey) { }

    public record RingSetupRequest(@NotBlank String ringId,
            @NotNull SetupKind kind,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String placementId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String courseId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String imageFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) AgilityHubLevel agilityhubLevel,
            @NotNull @Valid List<String> levelIds,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate expectedUntil,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes) { }

    public record RenewalRequest(@Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate expectedUntil) { }

    public record BuildSessionRequest(@NotBlank String placementId,
            @NotNull Boolean live,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) BuildStrategy selectedStrategy) { }

    public record BuildObstacleRequest(@NotNull ObstacleStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @PositiveOrZero Long version) { }

    public record BuildJoinRequest(@NotBlank String code) { }

    public record InventoryRequest(@NotNull @Valid List<@NotNull InventoryItem> items) { }

    public record ChallengeAttemptRequest(@NotBlank String dogId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringSetupId,
            @NotNull @PositiveOrZero Long timeMs,
            @NotNull @PositiveOrZero Integer faults,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String videoUrl) { }

}
