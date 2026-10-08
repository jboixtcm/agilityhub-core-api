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
import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;

/** S16 explicit responses; club-local dates and UTC instants. Nullable values are sent as null. */
public final class CourseContracts {
    private CourseContracts() { }
    public record Course(String id,
            OwnerType ownerType,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String clubId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ownerAccountId,
            Visibility visibility,
            String name,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Map<String,String> nameI18n,
            Discipline discipline,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String designerName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate designedOn,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String eventName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) AgilityHubLevel agilityhubLevel,
            List<String> levelIds,
            List<String> tags,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes,
            CourseSource source,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String sourceCourseId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String sourceFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String imageFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String imageUrl,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String thumbnailFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String thumbnailUrl,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) JsonNode normalizedJson,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer schemaVersion,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Double designLengthM,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Double designWidthM,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Double canvasWidth,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Double canvasHeight,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String origin,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String courseGrade,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String courseType,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) SizeCategory sizeCategory,
            CourseStats stats,
            long version,
            Instant createdAt,
            String createdByAccountId,
            Instant updatedAt) { }

    @com.agilityhub.core.shared.application.contract.SparseListItem
    public record CourseListItem(String id,
            @Schema(requiredMode = NOT_REQUIRED) String name,
            @Schema(requiredMode = NOT_REQUIRED) Discipline discipline,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String level,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String designerName,
            @Schema(requiredMode = NOT_REQUIRED) CourseSource source,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String thumbnailUrl,
            @Schema(requiredMode = NOT_REQUIRED) CourseStats stats,
            @Schema(requiredMode = NOT_REQUIRED) OwnerType ownerType,
            @Schema(requiredMode = NOT_REQUIRED) List<ActiveRing> activeOnRings) { }

    public record CoursePage(List<CourseListItem> items,
            int page,
            int size,
            long totalItems,
            int totalPages,
            List<Filter> appliedFilters) { }

    @Schema(name = "CourseUploadUrl")
    public record UploadUrl(String uploadUrl,
            String fileKey) { }

    public record CalibrationLog(String id,
            String ringId,
            CalibrationOutcome outcome,
            double averageErrorCm,
            double maxErrorCm,
            int markerCount,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String deviceModel,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String osVersion,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String appVersion,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String arProvider,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes,
            JsonNode rawMetadata,
            String createdByAccountId,
            Instant createdAt) { }

    public record Placement(String id,
            String courseId,
            long courseVersion,
            String ringId,
            PlacementTransform transform,
            PlacementMode placementMode,
            double warningThresholdM,
            List<ResolvedObstacle> resolvedObstacles,
            List<PlacementWarning> warnings,
            boolean stale,
            PlacementStatus status,
            PlacementUse mode,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant scheduledAt,
            List<String> grades,
            List<DogSize> sizes,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String displayName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String name,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String activityId,
            List<String> classSessionIds,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String buildSheetFileKey,
            String createdByAccountId,
            Instant createdAt,
            Instant updatedAt,
            long version) { }

    public record RingSetup(String id,
            String ringId,
            SetupKind kind,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String placementId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String courseId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String imageFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String imageUrl,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) AgilityHubLevel agilityhubLevel,
            List<String> levelIds,
            Instant builtAt,
            String builtByAccountId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate expectedUntil,
            Instant expiresAt,
            SetupStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant dismantledAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String dismantledByAccountId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes,
            Instant createdAt) { }

    public record RingSetupCreated(RingSetup setup,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String replacedSetupId) { }

    public record RingSetupPage(List<RingSetup> items,
            int page,
            int size,
            long totalItems,
            int totalPages) { }

    public record MeRingSetupSummary(String id,
            SetupKind kind,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String levelLabel,
            Instant builtAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate expectedUntil,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String thumbnailUrl,
            boolean hasViewer) { }

    public record MeRingSetup(String ringId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) MeRingSetupSummary setup) { }

    public record BuildSession(String id,
            String placementId,
            String ringId,
            BuildStatus status,
            BuildStrategy selectedStrategy,
            boolean live,
            String startedByAccountId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant startedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant finishedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String lastKnownMarkerId,
            long localCacheVersion,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) JsonNode progressJson,
            String joinCode,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String joinToken,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant joinTokenRotatedAt,
            List<BuildObstacle> obstacles,
            List<BuildParticipant> participants,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String buildSheetFileKey,
            long version) { }

    public record ObstacleInventory(InventoryScope scope,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringId,
            List<InventoryItem> items,
            Instant updatedAt) { }

    public record Challenge(String id,
            String courseId,
            String title,
            String description,
            AgilityHubLevel agilityhubLevel,
            JsonNode rules,
            LocalDate validFrom,
            LocalDate validTo,
            ChallengeStatus status) { }

    public record ChallengeAttempt(String challengeId,
            String dogId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringSetupId,
            long timeMs,
            int faults,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String videoUrl,
            Instant submittedAt,
            AttemptStatus status) { }

    public record SchemaError(String path,
            String message) { }

    public record CourseModelInvalidDetails(List<SchemaError> schemaErrors) { }

    public record PlacementBlockedDetails(List<PlacementWarning> warnings) { }

    public record MemberRingSetup(String id,
            String ringId,
            SetupKind kind,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String placementId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String courseId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String imageFileKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String imageUrl,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) AgilityHubLevel agilityhubLevel,
            List<String> levelIds,
            Instant builtAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate expectedUntil,
            Instant expiresAt,
            SetupStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant dismantledAt,
            Instant createdAt) { }

}
