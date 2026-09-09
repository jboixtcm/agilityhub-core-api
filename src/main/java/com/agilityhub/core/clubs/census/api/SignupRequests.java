package com.agilityhub.core.clubs.census.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import com.agilityhub.core.shared.domain.Money;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static com.agilityhub.core.clubs.census.api.CensusResponses.*;

/** S04 input allowlists. Country, module and consent business rules belong to E3-T02/T03. */
public final class SignupRequests {
    private SignupRequests() { }

    public enum SignupIdDocumentType { DNI, NIE, PASSPORT, OTHER }
    public enum FirstMonthOption { TODAY, ALTERNATIVE }
    public record SignupIdDocument(@NotNull SignupIdDocumentType type, @NotBlank String value) { }
    public record SignupAddress(@NotBlank String street, @NotBlank String postalCode, @NotBlank String town) { }
    public record SignupPhone(@NotBlank String prefix, @NotBlank String number,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 30) String label) { }
    public record SignupPerson(@NotNull @Valid SignupIdDocument idDocument,
            @NotBlank @Size(max = 60) String firstName, @NotBlank @Size(max = 60) String lastName1,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 60) String lastName2,
            @NotNull LocalDate birthDate, @NotNull Gender gender,
            @NotNull @Size(min = 1, max = 2) List<@NotBlank @Email String> emails,
            @NotNull @Size(min = 1, max = 2) List<@Valid SignupPhone> phones,
            @NotNull @Valid SignupAddress address) { }
    public record SignupFile(@NotBlank String fileKey, @NotBlank String name) { }
    public record SignupDocument(@NotBlank @Schema(description = "Key from census.dogDocumentTypes; e.g. VACCINATION_CARD, INSURANCE, OTHER") String type,
            @NotNull @Size(max = 10) List<@Valid SignupFile> files) { }
    public record SignupDog(@NotBlank @Size(max = 40) String name, @NotNull Sex sex,
            @NotBlank @Size(max = 60) String breed,
            @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") @Schema(example = "2024-04") String birthMonth,
            @NotBlank String chip,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 1000) String notesToInstructors,
            @Schema(requiredMode = NOT_REQUIRED, description = "Public signup documents; add-dog uses its top-level documents array")
            @Size(max = 10) List<@Valid SignupDocument> documents) { }
    public record SignupFamilyGroupClaim(@NotBlank String holderName, @NotBlank String dogName, @NotNull Boolean leavePending) { }
    public record SignupPayment(@NotNull PaymentMethodType type,
            @Schema(requiredMode = NOT_REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY, example = "ES0000000000000000000000") @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String iban,
            @Schema(requiredMode = NOT_REQUIRED) String holderName,
            @Schema(requiredMode = NOT_REQUIRED) String holderTaxId,
            @Schema(requiredMode = NOT_REQUIRED, description = "Required for a monthly plan with BILLING") FirstMonthOption firstMonthOption) { }
    public record SignupPrivacyConsent(@NotNull Boolean accepted, @NotBlank String version) { }
    public record SignupImageConsent(@NotNull Boolean granted, @NotBlank String version) { }
    public record SignupConsents(@NotNull @Valid SignupPrivacyConsent privacyPolicy, @NotNull @Valid SignupImageConsent imageUse) { }
    public record SignupRequest(@NotBlank @Schema(example = "en") String locale,
            @Schema(requiredMode = NOT_REQUIRED, description = "Honeypot; nonempty input will return 202 without persistence in E3-T03", example = "") String website,
            @NotNull @Valid SignupPerson person, @NotNull @Valid SignupDog dog,
            @NotBlank @Schema(format = "uuid") String planId,
            @Schema(requiredMode = NOT_REQUIRED) @Valid SignupFamilyGroupClaim familyGroupClaim,
            @Schema(requiredMode = NOT_REQUIRED, description = "Omitted without BILLING") @Valid SignupPayment payment,
            @NotNull @Valid SignupConsents consents) { }
    public record IdentityCheckRequest(@NotNull @Valid SignupIdDocument idDocument,
            @NotNull @Size(min = 1, max = 2) List<@NotBlank @Email String> emails) { }
    public record UploadUrlRequest(@NotBlank String fileName, @NotBlank String contentType, @NotNull @Positive Long sizeBytes) { }
    public record FamilyGroupLookupRequest(@NotBlank String holderName, @NotBlank String dogName) { }
    public record AddDogSignupRequest(@NotNull @Valid SignupDog dog,
            @NotNull @Size(max = 10) List<@Valid SignupDocument> documents,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String planIdRequested,
            @Schema(requiredMode = NOT_REQUIRED) @Valid SignupConsents consents) { }
    public record ValidationDog(@NotBlank @Schema(format = "uuid") String dogId,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String levelId) { }
    public record ValidationRequest(@NotNull @PositiveOrZero Long version,
            @NotNull @Size(min = 1) List<@Valid ValidationDog> dogs,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String planId,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String priceId,
            @Schema(requiredMode = NOT_REQUIRED) LocalDate nextInvoiceDate,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String familyGroupId,
            @Schema(requiredMode = NOT_REQUIRED) Money upfrontAmountPaid) { }
    public record RejectionRequest(@NotNull @PositiveOrZero Long version, @NotBlank String reason) { }
}
