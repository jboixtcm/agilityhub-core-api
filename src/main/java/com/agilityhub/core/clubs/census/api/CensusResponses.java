package com.agilityhub.core.clubs.census.api;

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
public final class CensusResponses {
    private CensusResponses() { }

    public enum Gender { MALE, FEMALE, OTHER }
    public enum Sex { MALE, FEMALE }
    public enum MemberStatus { PENDING, ACTIVE, LEFT }
    public enum DogStatus { PENDING, ACTIVE, INACTIVE }
    public enum MemberRole { MEMBER, INSTRUCTOR, ADMIN }
    public enum PaymentMethodType { SEPA_DD, CARD, MANUAL }
    public record Address(
            @Schema(requiredMode = REQUIRED) String street,
            @Schema(requiredMode = REQUIRED) String postalCode,
            @Schema(requiredMode = REQUIRED) String city,
            String province,
            String country) { }
    public record IdDocument(
            @Schema(requiredMode = REQUIRED) String type,
            @Schema(requiredMode = REQUIRED) String number) { }
    public record ContactEmail(
            @Schema(requiredMode = REQUIRED) String email,
            @Schema(requiredMode = REQUIRED) boolean bounced) { }
    public record Phone(
            @Schema(requiredMode = REQUIRED) String prefix,
            @Schema(requiredMode = REQUIRED) String number,
            String label) { }
    @Schema(description = "No full IBAN, card credentials or setup intent IDs.")
    public record PaymentMethodView(
            @Schema(requiredMode = REQUIRED) PaymentMethodType type,
            String maskedAccount,
            String holderName,
            String channel) { }
    @Schema(description = "Derived presentation status; includes ERASED under S14.")
    public record DisplayStatus(
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(requiredMode = REQUIRED) String label) { }
    public record BookingBlock(
            @Schema(requiredMode = REQUIRED) boolean active,
            String reason,
            Instant since,
            @Schema(format = "uuid") String byAccountId) { }
    public record ImageRights(
            @Schema(requiredMode = REQUIRED) boolean granted,
            Instant at,
            String version,
            @Schema(format = "uuid") String byAccountId) { }
    public record PrivacyPolicyConsent(
            @Schema(requiredMode = REQUIRED) Instant acceptedAt,
            @Schema(requiredMode = REQUIRED) String version) { }
    public record MemberConsents(
            @Schema(requiredMode = REQUIRED) PrivacyPolicyConsent privacyPolicy,
            @Schema(requiredMode = REQUIRED) ImageRights imageRights) { }
    @Schema(description = "ADMIN projection. Payment details are masked. accountMissing means SEPA_DD without an IBAN.")
    public record Member(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(format = "uuid") String accountId,
            Integer memberNumber,
            IdDocument idDocument,
            @Schema(requiredMode = REQUIRED) String firstName,
            @Schema(requiredMode = REQUIRED) String lastName1,
            String lastName2,
            @Schema(requiredMode = REQUIRED) String fullName,
            @Schema(requiredMode = REQUIRED) Gender gender,
            @Schema(requiredMode = REQUIRED) LocalDate birthDate,
            @Schema(requiredMode = REQUIRED) List<ContactEmail> contactEmails,
            @Schema(requiredMode = REQUIRED) List<Phone> phones,
            @Schema(requiredMode = REQUIRED) Address address,
            PaymentMethodView paymentMethod,
            String maskedAccount,
            @Schema(format = "uuid") String planId,
            @Schema(format = "uuid") String priceId,
            LocalDate nextInvoiceDate,
            MemberConsents consents,
            String remarks,
            String internalNotes,
            @Schema(requiredMode = REQUIRED) MemberStatus status,
            @Schema(requiredMode = REQUIRED) DisplayStatus displayStatus,
            Instant joinedAt,
            LocalDate leaveDate,
            @Schema(requiredMode = REQUIRED) BookingBlock bookingBlock,
            @Schema(format = "uuid") String familyGroupId,
            @Schema(requiredMode = REQUIRED) List<MemberRole> roles,
            @Schema(requiredMode = REQUIRED) long version,
            boolean accountMissing,
            Instant erasedAt,
            @Schema(format = "uuid") String erasureRequestId) { }
    public record LevelSummary(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String name,
            String color) { }
    public record DogSummary(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String name,
            LevelSummary level) { }
    @Schema(description = "R-03-31: excludes paymentMethod, nextInvoiceDate, consents, internalNotes and booking-block reason.")
    public record MemberInstructorView(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            Integer memberNumber,
            String firstName,
            String lastName1,
            String lastName2,
            @Schema(requiredMode = REQUIRED) String fullName,
            @Schema(requiredMode = REQUIRED) List<ContactEmail> contactEmails,
            @Schema(requiredMode = REQUIRED) List<Phone> phones,
            Address address,
            @Schema(requiredMode = REQUIRED) MemberStatus status,
            @Schema(requiredMode = REQUIRED) DisplayStatus displayStatus,
            @Schema(requiredMode = REQUIRED) List<DogSummary> dogs,
            @Schema(requiredMode = REQUIRED) boolean bookingBlocked,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record NamedReference(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String name) { }
    public record ContactSummary(
            @Schema(requiredMode = REQUIRED) List<ContactEmail> emails,
            @Schema(requiredMode = REQUIRED) List<Phone> phones) { }
    @Schema(description = "ADMIN list projection; idDocument and account data are masked. INSTRUCTOR uses MemberInstructorView.")
    public record MemberListItem(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            Integer memberNumber,
            @Schema(requiredMode = REQUIRED) String fullName,
            @Schema(requiredMode = REQUIRED) List<DogSummary> dogs,
            NamedReference plan,
            @Schema(requiredMode = REQUIRED) DisplayStatus displayStatus,
            ContactSummary contact,
            PaymentMethodView paymentMethod,
            LocalDate nextInvoiceDate,
            NamedReference familyGroup,
            Instant joinedAt,
            LocalDate leaveDate,
            @Schema(requiredMode = REQUIRED) boolean bookingBlocked,
            ImageRights imageRights,
            List<MemberRole> roles,
            String city,
            String postalCode,
            List<String> pendingDocuments,
            Boolean freeTraining,
            LocalDate birthDate,
            Gender gender,
            String idDocument,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record License(
            @Schema(requiredMode = REQUIRED) String organisation,
            @Schema(requiredMode = REQUIRED) String number,
            String grade) { }
    public record InstructorNote(
            @Schema(requiredMode = REQUIRED) String text,
            @Schema(requiredMode = REQUIRED) Instant updatedAt) { }
    public record PackSummary(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) int remaining,
            @Schema(requiredMode = REQUIRED) int total,
            LocalDate expiresOn) { }
    public record DocumentFile(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String url,
            @Schema(requiredMode = REQUIRED) Instant uploadedAt) { }
    public enum DocumentState { PENDING, RECEIVED }
    public record DogDocument(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String type,
            @Schema(requiredMode = REQUIRED) String typeLabel,
            @Schema(requiredMode = REQUIRED) DocumentState state,
            @Schema(requiredMode = REQUIRED) List<DocumentFile> files,
            Instant lastReminderAt) { }
    @Schema(description = "Backoffice only. Never use this schema for /me dog responses.")
    public record Dog(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED, format = "uuid") String memberId,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String breed,
            @Schema(requiredMode = REQUIRED) Sex sex,
            @Schema(requiredMode = REQUIRED) LocalDate birthDate,
            @Schema(requiredMode = REQUIRED) String chip,
            String photoUrl,
            @Schema(format = "uuid") String levelId,
            Instant levelAssignedAt,
            Boolean freeTrainingOverride,
            InstructorNote instructorNote,
            @Schema(requiredMode = REQUIRED) List<License> licenses,
            @Schema(requiredMode = REQUIRED) DogStatus status,
            @Schema(requiredMode = REQUIRED) Instant registeredAt,
            Instant deactivatedAt,
            String deactivationReason,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record OwnerSummary(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String fullName,
            Integer memberNumber,
            @Schema(requiredMode = REQUIRED) MemberStatus status) { }
    public record LevelHistoryEntry(
            @Schema(requiredMode = REQUIRED, format = "uuid") String levelId,
            @Schema(requiredMode = REQUIRED) Instant from,
            Instant to,
            @Schema(format = "uuid") String byAccountId) { }
    public enum FreeTrainingSource { LEVEL, OVERRIDE }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record FreeTraining(
            @Schema(requiredMode = REQUIRED) boolean allowed,
            @Schema(requiredMode = REQUIRED) FreeTrainingSource source,
            @Schema(requiredMode = REQUIRED, types = {"boolean", "null"}) Boolean override) { }
    public record TasksSummary(
            @Schema(requiredMode = REQUIRED) int open,
            @Schema(requiredMode = REQUIRED) int completed) { }
    public record DogDetail(
            @Schema(requiredMode = REQUIRED) Dog dog,
            @Schema(requiredMode = REQUIRED) OwnerSummary owner,
            LevelSummary level,
            @Schema(requiredMode = REQUIRED) List<LevelHistoryEntry> levelHistory,
            @Schema(requiredMode = REQUIRED) FreeTraining freeTraining,
            @Schema(requiredMode = REQUIRED) List<DogDocument> documents,
            @Schema(requiredMode = REQUIRED) List<License> licenses,
            PackSummary pack,
            @Schema(requiredMode = REQUIRED) TasksSummary tasksSummary,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record DogListItem(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String breed,
            LevelSummary level,
            @Schema(requiredMode = REQUIRED) OwnerSummary owner,
            FreeTraining freeTraining,
            @Schema(requiredMode = REQUIRED) List<License> licenses,
            @Schema(requiredMode = REQUIRED) DisplayStatus displayStatus,
            @Schema(requiredMode = REQUIRED) Sex sex,
            @Schema(requiredMode = REQUIRED) double age,
            String chip,
            @Schema(requiredMode = REQUIRED) List<String> pendingDocuments,
            Instant levelAssignedAt,
            PackSummary pack,
            @Schema(requiredMode = REQUIRED) Instant registeredAt,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record FamilyDog(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String name,
            String levelCode) { }
    public record FamilyMember(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String fullName,
            Integer memberNumber,
            @Schema(requiredMode = REQUIRED) List<FamilyDog> dogs) { }
    public enum FamilyGroupStatus { ACTIVE, DISSOLVED }
    public record FamilyGroup(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED, format = "uuid") String holderMemberId,
            @Schema(requiredMode = REQUIRED) List<String> memberIds,
            @Schema(requiredMode = REQUIRED) List<FamilyMember> members,
            @Schema(requiredMode = REQUIRED) FamilyGroupStatus status,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record MeFamilyGroup(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) FamilyMember holder,
            @Schema(requiredMode = REQUIRED) List<FamilyMember> members) { }
    @Schema(description = "Owner/family projection, with no chip, private remarks or internal ownership fields.")
    public record MeDog(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String breed,
            @Schema(requiredMode = REQUIRED) Sex sex,
            @Schema(requiredMode = REQUIRED) double ageYears,
            String photoUrl,
            LevelSummary level,
            InstructorNote instructorNote,
            TasksSummary tasks,
            @Schema(requiredMode = REQUIRED) List<DogDocument> documents,
            @Schema(requiredMode = REQUIRED) boolean freeTrainingAllowed,
            @Schema(requiredMode = REQUIRED) List<License> licenses,
            PackSummary pack) { }
    public record MeDogs(
            @Schema(requiredMode = REQUIRED) List<MeDog> dogs,
            @Schema(requiredMode = REQUIRED) boolean canAddDog) { }
    public record MeProfile(
            @Schema(requiredMode = REQUIRED) String idDocumentMasked,
            @Schema(requiredMode = REQUIRED) String firstName,
            @Schema(requiredMode = REQUIRED) String lastName1,
            String lastName2,
            @Schema(requiredMode = REQUIRED) List<ContactEmail> contactEmails,
            @Schema(requiredMode = REQUIRED) List<Phone> phones,
            @Schema(requiredMode = REQUIRED) Address address,
            PaymentMethodView paymentMethod,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record OverviewDog(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String breed,
            LevelSummary level,
            @Schema(requiredMode = REQUIRED) boolean freeTrainingAllowed,
            PackSummary pack,
            @Schema(requiredMode = REQUIRED) List<String> pendingDocuments) { }
    public record InvoiceSummary(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) LocalDate date,
            @Schema(requiredMode = REQUIRED) Money amount,
            @Schema(requiredMode = REQUIRED) String status) { }
    public record AuditSummary(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) Instant at,
            @Schema(requiredMode = REQUIRED) String action,
            @Schema(requiredMode = REQUIRED) String actorRole,
            String actorName) { }
    public record NextInvoice(
            @Schema(requiredMode = REQUIRED) LocalDate date,
            @Schema(requiredMode = REQUIRED) Money amount) { }
    @Schema(description = "Cross-vertical notification preference payload remains owned by S11.")
    public record MemberOverview(
            @Schema(requiredMode = REQUIRED) Member member,
            FamilyGroup familyGroup,
            @Schema(requiredMode = REQUIRED) List<OverviewDog> dogs,
            @Schema(requiredMode = REQUIRED) Map<String, Object> notificationPreferences,
            @Schema(requiredMode = REQUIRED) @Size(max = 2) List<InvoiceSummary> recentInvoices,
            @Schema(requiredMode = REQUIRED) long invoicesCount,
            @Schema(requiredMode = REQUIRED) @Size(max = 2) List<AuditSummary> recentAudit,
            NextInvoice nextInvoice) { }
    public record LevelChangeResult(
            @Schema(requiredMode = REQUIRED) LevelSummary level,
            @Schema(requiredMode = REQUIRED) Instant levelAssignedAt,
            @Schema(requiredMode = REQUIRED) LevelWarnings warnings) { }
    public record LevelWarnings(
            @Schema(requiredMode = REQUIRED) int futureBookingsOutsideLevel) { }
    public record PhotoResponse(
            @Schema(requiredMode = REQUIRED) String photoUrl) { }
    public record AccessResendResponse(
            @Schema(requiredMode = REQUIRED) String sentTo) { }
    public record RolesResponse(
            @Schema(requiredMode = REQUIRED) List<MemberRole> roles) { }

}
