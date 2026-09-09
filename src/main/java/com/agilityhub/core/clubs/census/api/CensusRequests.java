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
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
/** E2 public contracts. Implementations map explicit allowlists into these DTOs. */
public final class CensusRequests {
    private CensusRequests() { }

    public record MemberPatch(
            @Schema(requiredMode = NOT_REQUIRED) IdDocument idDocument,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 60) String firstName,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 60) String lastName1,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 60) String lastName2,
            @Schema(requiredMode = NOT_REQUIRED) Gender gender,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate birthDate,
            @Schema(requiredMode = NOT_REQUIRED) List<ContactEmailInput> contactEmails,
            @Schema(requiredMode = NOT_REQUIRED) List<Phone> phones,
            @Schema(requiredMode = NOT_REQUIRED) Address address,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 2000) String remarks,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 2000) String internalNotes,
            @Schema(requiredMode = NOT_REQUIRED) ConsentPatch consents,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record ConsentPatch(
            @Schema(requiredMode = NOT_REQUIRED) ImageRightsPatch imageRights) { }
    public record ImageRightsPatch(
            @Schema(requiredMode = REQUIRED) @NotNull Boolean granted) { }
    public record ContactEmailInput(
            @Schema(requiredMode = REQUIRED) @NotBlank @Email String email) { }
    public record MeProfilePatch(
            @Schema(requiredMode = NOT_REQUIRED) @Size(min = 1, max = 2) List<@Valid ContactEmailInput> contactEmails,
            @Schema(requiredMode = NOT_REQUIRED) @Size(min = 1, max = 2) List<@Valid Phone> phones,
            @Schema(requiredMode = NOT_REQUIRED) @Valid Address address,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record PaymentMethodPatch(
            @Schema(requiredMode = REQUIRED) @NotNull PaymentMethodType type,
            @Schema(requiredMode = NOT_REQUIRED) @Valid SepaInput sepa,
            @Schema(requiredMode = NOT_REQUIRED) @Valid CardInput card,
            @Schema(requiredMode = NOT_REQUIRED) @Valid ManualInput manual) { }
    public record SepaInput(
            @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
            @Schema(requiredMode = NOT_REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY) String iban,
            @Schema(requiredMode = NOT_REQUIRED) String holderName,
            @Schema(requiredMode = NOT_REQUIRED) String holderTaxId) { }
    public record CardInput(
            @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
            @Schema(requiredMode = REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank String stripeSetupIntentId) { }
    public record ManualInput(
            @Schema(requiredMode = REQUIRED) @NotBlank String channel) { }
    public record BookingBlockRequest(
            @Schema(requiredMode = REQUIRED) @NotBlank @Size(max = 200) String reason) { }
    public record ReasonRequest(
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String reason) { }
    public record RolesRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(min = 1) List<@NotNull MemberRole> roles) { }
    public record DogPatch(
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 40) String name,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 60) String breed,
            @Schema(requiredMode = NOT_REQUIRED) Sex sex,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate birthDate,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 20) String chip,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 80) String handlerName,
            @Schema(requiredMode = NOT_REQUIRED) List<License> licenses,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record DogLevelRequest(
            @Schema(requiredMode = REQUIRED) @NotBlank String levelId) { }
    public record FreeTrainingRequest(
            @Schema(requiredMode = REQUIRED, types = {"boolean", "null"}) Boolean override) { }
    public record DogTransferRequest(
            @Schema(requiredMode = REQUIRED) @NotBlank String toMemberId,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String reason) { }
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
            @Schema(requiredMode = REQUIRED) @NotNull List<String> memberIds) { }
    public record FamilyGroupUpdate(
            @Schema(requiredMode = REQUIRED) @NotBlank String holderMemberId,
            @Schema(requiredMode = REQUIRED) @NotNull List<String> memberIds,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record InstructorNoteRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 2000) String text) { }

}
