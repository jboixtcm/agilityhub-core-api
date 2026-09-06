package com.agilityhub.core.platform.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import com.agilityhub.core.shared.domain.Money;
import static com.agilityhub.core.shared.application.contract.ApiContracts.*;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** E2 public contracts. Implementations map explicit allowlists into these DTOs. */
public final class AuditContracts {
    private AuditContracts() { }

    /** Wire catalog from S14 R-14-09; does not enable additional audited mutations. */
    @Schema(name = "AuditAction", enumAsRef = true)
    public enum AuditActionName {
        MEMBER_VALIDATED,
        SIGNUP_REJECTED,
        SIGNUP_EDITED,
        MEMBER_UPDATED,
        MEMBER_PAYMENT_METHOD_CHANGED,
        MEMBER_STATUS_CHANGED,
        MEMBER_ROLES_CHANGED,
        MEMBER_CONSENT_CHANGED,
        BOOKING_BLOCK_SET,
        BOOKING_BLOCK_CLEARED,
        ACCESS_RESENT,
        DOG_UPDATED,
        DOG_LEVEL_CHANGED,
        DOG_FREE_TRAINING_CHANGED,
        DOG_TRANSFERRED,
        DOG_DEACTIVATED,
        DOG_REACTIVATED,
        DOG_DOCUMENT_FILE_REMOVED,
        FAMILY_GROUP_CHANGED,
        MEMBER_PLAN_CHANGED,
        INVOICE_MARKED_PAID,
        INVOICE_MARKED_FAILED,
        INVOICE_CANCELLED,
        REMITTANCE_GENERATED,
        REMITTANCE_ROLLED_BACK,
        UPFRONT_PAYMENT_RECORDED,
        PAYMENT_REFUNDED,
        IMPERSONATION_STARTED,
        ACCOUNT_EMAIL_STATUS_CHANGED,
        BOOKING_CREATED_BY_CLUB,
        BOOKING_CANCELLED_BY_CLUB,
        BOOKING_CANCELLED_LATE,
        TRAINING_BOOKED_BY_CLUB,
        TRAINING_CANCELLED_BY_CLUB,
        ATTENDANCE_OVERRIDDEN,
        WEEK_VALIDATED,
        CLASS_CANCELLED,
        CLASS_UPDATED_WITH_BOOKINGS,
        CLASS_RISK_EXEMPTION_CHANGED,
        RING_BLOCK_CREATED,
        RING_BLOCK_CANCELLED,
        TEMPLATE_CLASS_DELETED,
        ACTIVITY_PUBLISHED,
        ACTIVITY_CANCELLED,
        INACTIVITY_RESOLVED,
        LEAVE_RESOLVED,
        LEAVE_CANCELLED,
        PARAMETER_CHANGED,
        CLUB_UPDATED,
        CLUB_MODULES_CHANGED,
        CLUB_STATUS_CHANGED,
        CATALOG_CHANGED,
        ANNOUNCEMENT_SENT,
        DATA_EXPORTED,
        MEMBER_DATA_EXPORTED,
        ERASURE_REQUESTED,
        ERASURE_CANCELLED,
        MEMBER_ERASED,
        ACCOUNT_ERASED
    }
    public enum AuditActorRole { ADMIN, INSTRUCTOR, MEMBER, SYSTEM, PLATFORM, WEBHOOK }
    public enum AuditOrigin { APP, BACKOFFICE, SYSTEM, WEBHOOK }
    @Schema(description = "Values are masked by the audit writer.")
    public record AuditChange(
            @Schema(requiredMode = REQUIRED) String path,
            @Schema(requiredMode = REQUIRED) Object before,
            @Schema(requiredMode = REQUIRED) Object after) { }
    public record AuditEntryListItem(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(format = "uuid") String clubId,
            @Schema(requiredMode = REQUIRED) Instant at,
            @Schema(format = "uuid") String actorAccountId,
            String actorName,
            @Schema(requiredMode = REQUIRED) AuditActorRole actorRole,
            @Schema(format = "uuid") String impersonatedMemberId,
            String impersonatedName,
            @Schema(requiredMode = REQUIRED) AuditOrigin origin,
            @Schema(requiredMode = REQUIRED) String entityType,
            @Schema(requiredMode = REQUIRED, format = "uuid") String entityId,
            String entityLabel,
            @Schema(format = "uuid") String memberId,
            @Schema(requiredMode = REQUIRED) AuditActionName action,
            @Schema(requiredMode = REQUIRED) List<AuditChange> changes,
            String reason) { }
    public record AuditEntry(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(format = "uuid") String clubId,
            @Schema(requiredMode = REQUIRED) Instant at,
            @Schema(format = "uuid") String actorAccountId,
            String actorName,
            @Schema(requiredMode = REQUIRED) AuditActorRole actorRole,
            @Schema(format = "uuid") String impersonatedMemberId,
            String impersonatedName,
            @Schema(requiredMode = REQUIRED) AuditOrigin origin,
            @Schema(requiredMode = REQUIRED) String entityType,
            @Schema(requiredMode = REQUIRED, format = "uuid") String entityId,
            String entityLabel,
            @Schema(format = "uuid") String memberId,
            @Schema(requiredMode = REQUIRED) AuditActionName action,
            @Schema(requiredMode = REQUIRED) List<AuditChange> changes,
            String reason,
            Map<String, Object> details,
            String ip,
            String userAgent,
            @Schema(requiredMode = REQUIRED, format = "uuid") String traceId,
            @Schema(requiredMode = REQUIRED) List<String> eventIds) { }
    public record ConsentHistoryEntry(
            @Schema(requiredMode = REQUIRED) String type,
            @Schema(requiredMode = REQUIRED) boolean granted,
            @Schema(requiredMode = REQUIRED) String version,
            @Schema(requiredMode = REQUIRED) Instant at,
            @Schema(requiredMode = REQUIRED) String locale,
            String ipHash,
            @Schema(requiredMode = REQUIRED) String source) { }
    public enum ErasureScope { MEMBER, ACCOUNT }
    public enum ErasureSource { ADMIN, PLATFORM, RETENTION }
    public enum ErasureStatus { SCHEDULED, BLOCKED, ERASING, ERASED, CANCELLED }
    public record ErasureRequest(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(format = "uuid") String clubId,
            @Schema(requiredMode = REQUIRED) ErasureScope scope,
            @Schema(format = "uuid") String accountId,
            @Schema(requiredMode = REQUIRED) List<String> memberIds,
            @Schema(requiredMode = REQUIRED) List<String> clubIds,
            @Schema(requiredMode = REQUIRED) ErasureSource source,
            @Schema(format = "uuid") String requestedByAccountId,
            @Schema(requiredMode = REQUIRED) Instant requestedAt,
            String reason,
            @Schema(requiredMode = REQUIRED) ErasureStatus status,
            @Schema(requiredMode = REQUIRED) Instant executeAt,
            @Schema(requiredMode = REQUIRED) List<String> blockReasons,
            Instant erasedAt,
            Instant cancelledAt,
            @Schema(format = "uuid") String cancelledByAccountId,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record ErasureInput(
            @Size(max = 500) String reason) { }
    public record SecurityEventView(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) com.agilityhub.core.shared.application.SecurityEvents.Type type,
            @Schema(format = "uuid") String accountId,
            @Schema(format = "uuid") String clubId,
            String ip,
            String userAgent,
            String route,
            Map<String, Object> details,
            @Schema(requiredMode = REQUIRED) Instant at) { }

}
