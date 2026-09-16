package com.agilityhub.core.clubs.activities.api;

import com.agilityhub.core.clubs.activities.domain.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.*;
import java.util.List;
import java.util.Map;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** S07 allowlisted wire projections; the public graph has no registration or member data. */
public final class ActivityContracts {
    private ActivityContracts() { }
    public record Activity(String id, String slug, ActivityState state, ActivityType type,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Map<String, String> typeLabel, String typeDisplay,
            String title, Map<String, String> titleI18n,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String shortDescription,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Map<String, String> shortDescriptionI18n,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String longDescriptionHtml,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Map<String, String> longDescriptionI18n,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ActivityImage image, List<ActivityDocument> documents,
            ActivityLocation location, List<String> ringIds, List<ActivityRing> rings, boolean allRings,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate date,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String startTime,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String endTime,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant startsAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant endsAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ActivityRingBlockWindow ringBlockWindow,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate registrationFrom,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate registrationTo, boolean registrationOpen,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer minPlaces,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer maxPlaces,
            List<String> levelIds, List<String> levelNames, boolean waitlistEnabled, ActivityVisibility visibility,
            @io.swagger.v3.oas.annotations.media.ArraySchema(maxItems = 0, arraySchema = @Schema(description = "Always empty in R1")) List<Void> priceTiers,
            ActivityCounters counters, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer freeSeats,
            boolean belowMinimum, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String publicUrl,
            List<String> ringBlockIds, @Schema(requiredMode = NOT_REQUIRED, description = "Only with COURSES") List<String> placementIds,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant publishedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ActivityCancellation cancellation,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "ADMIN only; omitted for INSTRUCTOR") String internalNotes,
            long version) { }
    public record ActivityListItem(String id, String title, @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate date,
            List<ActivityRing> rings, ActivityCounters registrations, ActivityState state, ActivityType type, String slug,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate registrationTo, Instant createdAt) { }
    public enum ActivityVisibility { MEMBERS }
    public record ActivityImage(String fileId, String name, String url) { }
    public record ActivityImageResult(ActivityImage image) { }
    public record ActivityDocument(String id, String name, String url) { }
    public record ActivityLocation(boolean atClub, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String name,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String address, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String url) { }
    public record ActivityRing(String id, String name, String color) { }
    public record ActivityRingBlockWindow(String fromTime, String toTime) { }
    public record ActivityCounters(int active, int waiting) { }
    public record ActivityCancellation(ActivityCancellationReason reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String adminText, Instant at, String byAccountId, int affectedCount) { }
    public record RingConflicts(List<ActivityRingConflict> conflicts, List<ActivityTrainingBooking> trainingBookings) { }
    public enum ActivityConflictType { CLASS, RING_BLOCK }
    public record ActivityRingConflict(String ringId, ActivityConflictType type, String id, Instant from, Instant to,
            String label, @Schema(requiredMode = NOT_REQUIRED) Integer bookedCount) { }
    public record ActivityTrainingBooking(String bookingId, String ringId, Instant from, Instant to, String memberName, String dogName) { }
    public record ActivityCancellationPreview(List<ActivityCancellationRecipient> registrations, int activeCount, int waitingCount) { }
    public record ActivityCancellationRecipient(String registrationId, String memberName, RegistrationState state,
            List<String> channels, int phoneCount) { }
    public record ActivityRegistrationListItem(String registrationId, ActivityRegistrationMember member, RegistrationState state,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer position, RegistrationOrigin origin, Instant registeredAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant cancelledAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) RegistrationCancelReason cancelReason) { }
    public record ActivityRegistrationMember(String id, String fullName, String memberNumber, List<ActivityPhone> phones, List<String> emails) { }
    public record ActivityPhone(String prefix, String number, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String label) { }
    public record ActivityRegistration(String id, String activityId, String memberId, RegistrationState state, RegistrationOrigin origin,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer position, RegisteredActivity activity, Instant registeredAt,
            ActivityRegisteredBy registeredBy, Instant cancellableUntil,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ActivityRegistrationCancellation cancellation,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ActivityImpersonation impersonation) { }
    public record RegisteredActivity(String id, String title, String startsAtLocal, String endsAtLocal, String placeLabel) { }
    public record ActivityRegisteredBy(String displayName, boolean viaClub) { }
    public record ActivityImpersonation(String actorAccountId, String memberId) { }
    public record ActivityRegistrationCancellation(RegistrationCancelReason reason, Instant at, RegistrationCancelledByRole byRole) { }
    public enum RegistrationCancelledByRole { MEMBER, ADMIN, SYSTEM }
    public record ActivityRegistrationSummary(String id, String activityId, RegistrationState state, RegistrationOrigin origin,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer position, RegisteredActivity activity, Instant registeredAt,
            Instant cancellableUntil, @Schema(requiredMode = NOT_REQUIRED, nullable = true) ActivityRegistrationCancellation cancellation) { }
    public record MeActivities(List<ActivityRow> bookable, List<ActivityRegistrationSummary> mine) { }
    public enum ActivityRowState { OPEN, FULL_WAITLIST, FULL, NOT_BOOKABLE }
    public record ActivityRow(String id, String title, String typeLabel, String startsAtLocal, String endsAtLocal,
            String placeLabel, ActivityRowState rowState, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String notBookableReason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer freeSeats, int waiting, boolean waitlistEnabled) { }
    public record MemberActivityDetail(String id, String slug, ActivityState state, ActivityType type, String typeDisplay,
            String title, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String shortDescription,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String longDescriptionHtml,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ActivityImage image, List<ActivityDocument> documents,
            ActivityLocation location, List<ActivityRing> rings, boolean allRings, LocalDate date,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String startTime,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String endTime, Instant startsAt, Instant endsAt,
            LocalDate registrationFrom, LocalDate registrationTo, boolean registrationOpen,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer minPlaces,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer maxPlaces, List<String> levelNames, boolean waitlistEnabled,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer freeSeats,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) ActivityRegistration myRegistration, ActivityRowState rowState,
            Instant cancellableUntil) { }
    public record PublicActivities(List<PublicActivity> items) { }
    public record PublicActivity(String slug, ActivityState state, ActivityType type, String typeLabel, String title,
            Map<String, String> titleI18n, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String shortDescription,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String longDescriptionHtml,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String longDescriptionText,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String imageUrl, List<PublicActivityDocument> documents,
            ActivityLocation location, List<String> ringNames, LocalDate date,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String startTime,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String endTime, String timeZone,
            PublicActivityRegistration registration, PublicActivityPlaces places, List<String> levels, String publicUrl) { }
    public record PublicActivityDocument(String name, String url) { }
    public record PublicActivityRegistration(LocalDate from, LocalDate to, boolean open, ActivityRegistrationChannel channel) { }
    public enum ActivityRegistrationChannel { APP }
    public record PublicActivityPlaces(@Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer max,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer free, boolean waitlist) { }
}
