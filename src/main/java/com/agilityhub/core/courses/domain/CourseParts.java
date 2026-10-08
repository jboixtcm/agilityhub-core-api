package com.agilityhub.core.courses.domain;

import com.agilityhub.core.courses.domain.CourseTypes.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** S16 value objects shared by storage and the explicit HTTP projections. */
public final class CourseParts {
    private CourseParts() { }
    public record PointM(@jakarta.validation.constraints.NotNull Double x,
            @jakarta.validation.constraints.NotNull Double y) { }

    public record CourseBbox(double w,
            double h) { }

    public record CourseStats(int obstacleCount,
            double pathLengthM,
            CourseBbox bbox) { }

    public record ActiveRing(String id,
            String name) { }

    public record PlacementTransform(@jakarta.validation.constraints.NotNull Double offsetXM,
            @jakarta.validation.constraints.NotNull Double offsetYM,
            @jakarta.validation.constraints.NotNull Double rotationDeg,
            @jakarta.validation.constraints.NotNull Boolean flipX,
            @jakarta.validation.constraints.NotNull Boolean flipY) { }

    public record ResolvedObstacle(@jakarta.validation.constraints.NotBlank String obstacleId,
            @jakarta.validation.constraints.NotNull ObstacleType type,
            @jakarta.validation.constraints.NotNull Double x,
            @jakarta.validation.constraints.NotNull Double y,
            @jakarta.validation.constraints.NotNull Double rotationDeg,
            @jakarta.validation.constraints.NotNull @jakarta.validation.Valid List<Integer> numbers) { }

    public record PlacementWarning(@jakarta.validation.constraints.NotBlank String id,
            @jakarta.validation.constraints.NotNull WarningRule ruleId,
            @jakarta.validation.constraints.NotNull WarningSeverity severity,
            @jakarta.validation.constraints.NotBlank String message,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String obstacleSourceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String courseNoGoZoneId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String ringNoGoZoneId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String doorId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Double distanceMeters,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Double thresholdMeters) { }

    public record RingDoor(@jakarta.validation.constraints.NotBlank String id,
            @jakarta.validation.constraints.NotBlank String label,
            @jakarta.validation.constraints.NotNull DoorSide side,
            @jakarta.validation.constraints.NotNull Double startM,
            @jakarta.validation.constraints.NotNull Double endM,
            @jakarta.validation.constraints.NotNull DoorFlow flow,
            @jakarta.validation.constraints.NotNull Double clearanceM,
            @jakarta.validation.constraints.NotNull @jakarta.validation.Valid List<PointM> polygonPointsM,
            @jakarta.validation.constraints.NotNull Boolean isActive,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes) { }

    public record RingNoGoZone(@jakarta.validation.constraints.NotBlank String id,
            @jakarta.validation.constraints.NotBlank String label,
            @jakarta.validation.constraints.NotNull @jakarta.validation.Valid List<PointM> polygonPointsM,
            @jakarta.validation.constraints.NotNull Double warningMarginM) { }

    public record RingMarker(@jakarta.validation.constraints.NotBlank String id,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-F]") String label,
            @jakarta.validation.constraints.NotBlank String markerUid,
            @jakarta.validation.constraints.NotBlank String role,
            @jakarta.validation.constraints.NotNull Double xM,
            @jakarta.validation.constraints.NotNull Double yM,
            @jakarta.validation.constraints.NotNull Double zM,
            @jakarta.validation.constraints.NotNull Double rotationYDeg,
            @jakarta.validation.constraints.NotNull Double physicalWidthM,
            @jakarta.validation.constraints.NotNull Double physicalHeightM,
            @jakarta.validation.constraints.NotNull Boolean isFixed,
            @jakarta.validation.constraints.NotNull Boolean isActive,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(586) Integer aprilTagId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes) { }

    public record RingGeometry(double lengthM,
            double widthM,
            double borderClearanceM,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Double orientationDeg,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Surface surface,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes,
            List<RingDoor> doors,
            List<RingNoGoZone> noGoZones,
            List<RingMarker> markers,
            long version,
            Instant updatedAt) { }

    public record BuildObstacle(String obstacleId,
            ObstacleStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String byAccountId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant at,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String lastUpdatedClientSessionId) { }

    public record BuildParticipant(String accountId,
            Instant joinedAt,
            JoinedVia joinedVia) { }

    public record InventoryItem(@jakarta.validation.constraints.NotNull ObstacleType type,
            @jakarta.validation.constraints.NotNull Integer count,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notes) { }

}
