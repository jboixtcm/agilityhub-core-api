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
import static com.agilityhub.core.clubs.census.api.CensusResponses.*;
/** E2 public contracts. Implementations map explicit allowlists into these DTOs. */
public final class CensusRequests {
    private CensusRequests() { }

    public record MemberPatch(
            IdDocument idDocument,
            @Size(max = 60) String firstName,
            @Size(max = 60) String lastName1,
            @Size(max = 60) String lastName2,
            Gender gender,
            LocalDate birthDate,
            List<ContactEmailInput> contactEmails,
            List<Phone> phones,
            Address address,
            @Size(max = 2000) String remarks,
            @Size(max = 2000) String internalNotes,
            ConsentPatch consents,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record ConsentPatch(
            ImageRightsPatch imageRights) { }
    public record ImageRightsPatch(
            @Schema(requiredMode = REQUIRED) @NotNull Boolean granted) { }
    public record ContactEmailInput(
            @Schema(requiredMode = REQUIRED) @NotBlank @Email String email) { }
    public record MeProfilePatch(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 1, max = 2) List<@Valid ContactEmailInput> contactEmails,
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 1, max = 2) List<@Valid Phone> phones,
            @Schema(requiredMode = REQUIRED) @NotNull @Valid Address address,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record PaymentMethodPatch(
            @Schema(requiredMode = REQUIRED) @NotNull PaymentMethodType type,
            @Valid SepaInput sepa,
            @Valid CardInput card,
            @Valid ManualInput manual) { }
    public record SepaInput(
            @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
            @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String iban,
            String holderName,
            String holderTaxId) { }
    public record CardInput(
            @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
            @Schema(requiredMode = REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank String stripeSetupIntentId) { }
    public record ManualInput(
            @Schema(requiredMode = REQUIRED) @NotBlank String channel) { }
    public record BookingBlockRequest(
            @Schema(requiredMode = REQUIRED) @NotBlank @Size(max = 200) String reason) { }
    public record ReasonRequest(
            @Size(max = 500) String reason) { }
    public record RolesRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 1) List<MemberRole> roles) { }
    public record DogPatch(
            @Size(max = 40) String name,
            @Size(max = 60) String breed,
            Sex sex,
            LocalDate birthDate,
            @Size(max = 20) String chip,
            List<License> licenses,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record DogLevelRequest(
            @Schema(requiredMode = REQUIRED) @NotBlank String levelId) { }
    public record FreeTrainingRequest(
            @Schema(requiredMode = REQUIRED, types = {"boolean", "null"}) Boolean override) { }
    public record DogTransferRequest(
            @Schema(requiredMode = REQUIRED) @NotBlank String toMemberId,
            @Size(max = 500) String reason) { }
    public record FileKeyRequest(
            @Schema(requiredMode = REQUIRED) @NotBlank String fileKey) { }
    public record DogDocumentRequest(
            @Schema(requiredMode = REQUIRED) @NotBlank String type,
            @Schema(requiredMode = REQUIRED) @NotBlank @Size(max = 80) String name,
            @Schema(requiredMode = REQUIRED) @NotBlank String fileKey) { }
    public record DocumentReminderRequest(
            @Schema(requiredMode = REQUIRED) @NotBlank String type) { }
    public record FamilyGroupRequest(
            @Schema(requiredMode = REQUIRED) @NotBlank String holderMemberId,
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 2) List<String> memberIds) { }
    public record FamilyGroupUpdate(
            @Schema(requiredMode = REQUIRED) @NotBlank String holderMemberId,
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 2) List<String> memberIds,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record InstructorNoteRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 2000) String text) { }

}
