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
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** E2 public contracts. Implementations map explicit allowlists into these DTOs. */
public final class SettingsContracts {
    private SettingsContracts() { }

    public enum ParameterEditor { CLUB, PLATFORM }
    public record Parameter(
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED) String type,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) String help,
            @Schema(requiredMode = REQUIRED) Object value,
            @Schema(requiredMode = REQUIRED) boolean isOverride,
            @Schema(requiredMode = NOT_REQUIRED) String scopeRef,
            @Schema(requiredMode = NOT_REQUIRED) LastChange lastChange,
            @Schema(requiredMode = REQUIRED) ParameterEditor editableBy,
            @Schema(requiredMode = REQUIRED) Map<String, Object> constraints,
            @Schema(requiredMode = NOT_REQUIRED) String module,
            @Schema(requiredMode = REQUIRED) long version,
            @Schema(requiredMode = REQUIRED) @com.fasterxml.jackson.annotation.JsonProperty("default") Object defaultValue,
            @Schema(requiredMode = REQUIRED) String block,
            @Schema(requiredMode = REQUIRED) List<ParameterHistoryEntry> history) { }
    public record ParameterHistoryEntry(
            @Schema(requiredMode = REQUIRED) Object value,
            @Schema(requiredMode = REQUIRED) Instant changedAt,
            @Schema(requiredMode = REQUIRED, format = "uuid") String changedByAccountId,
            @Schema(requiredMode = NOT_REQUIRED) String reason) { }
    public record ParameterBlock(
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED) String title,
            @Schema(requiredMode = REQUIRED) List<Parameter> rows) { }
    public record Parameters(
            @Schema(requiredMode = REQUIRED) List<ParameterBlock> blocks,
            @Schema(requiredMode = NOT_REQUIRED) LastChange lastChange) { }
    public record ParameterDefinition(
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED) String type,
            @Schema(requiredMode = REQUIRED) @com.fasterxml.jackson.annotation.JsonProperty("default") Object defaultValue,
            @Schema(requiredMode = REQUIRED) String block,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) String help,
            @Schema(requiredMode = REQUIRED) Map<String, Object> constraints,
            @Schema(requiredMode = REQUIRED) List<String> modules,
            @Schema(requiredMode = REQUIRED) String scope,
            @Schema(requiredMode = REQUIRED) ParameterEditor editableBy,
            @Schema(requiredMode = REQUIRED) boolean restartRequired) { }
    public record ClubAddress(
            @Schema(requiredMode = NOT_REQUIRED) String street,
            @Schema(requiredMode = NOT_REQUIRED) String postalCode,
            @Schema(requiredMode = NOT_REQUIRED) String city,
            @Schema(requiredMode = NOT_REQUIRED) String region,
            @Schema(requiredMode = NOT_REQUIRED) String country) { }
    public record ClubDomain(
            @Schema(requiredMode = REQUIRED) String host,
            @Schema(requiredMode = REQUIRED) String app,
            @Schema(requiredMode = NOT_REQUIRED) Instant verifiedAt,
            @Schema(requiredMode = REQUIRED) boolean primary) { }
    public record ClubLegal(
            @Schema(requiredMode = REQUIRED) String privacyPolicyUrl,
            @Schema(requiredMode = NOT_REQUIRED) String imageConsentText,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, String> imageConsentTextI18n,
            @Schema(requiredMode = REQUIRED) String legalTextsVersion) { }
    @Schema(description = "Configuration status only; provider credentials and API key hashes are never exposed.")
    public record PaymentProviderSummary(
            @Schema(requiredMode = REQUIRED) boolean configured,
            @Schema(requiredMode = REQUIRED) boolean enabled) { }
    public record ClubSettings(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String slug,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = NOT_REQUIRED) String legalName,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String taxId,
            @Schema(requiredMode = NOT_REQUIRED) ClubAddress address,
            @Schema(requiredMode = NOT_REQUIRED) String contactEmail,
            @Schema(requiredMode = NOT_REQUIRED) String contactPhone,
            @Schema(requiredMode = NOT_REQUIRED) String websiteUrl,
            @Schema(requiredMode = REQUIRED) List<String> locales,
            @Schema(requiredMode = REQUIRED) String defaultLocale,
            @Schema(requiredMode = REQUIRED) String timeZone,
            @Schema(requiredMode = REQUIRED) String currency,
            @Schema(requiredMode = REQUIRED) String countryProfile,
            @Schema(requiredMode = REQUIRED) List<ClubDomain> domains,
            @Schema(requiredMode = REQUIRED) com.agilityhub.core.platform.domain.Theme theme,
            @Schema(requiredMode = NOT_REQUIRED) ClubPwa pwa,
            @Schema(requiredMode = REQUIRED) List<String> modules,
            @Schema(requiredMode = NOT_REQUIRED) Map<String, PaymentProviderSummary> paymentProviders,
            @Schema(requiredMode = NOT_REQUIRED) ClubLegal legal,
            @Schema(requiredMode = REQUIRED) String status,
            @Schema(requiredMode = NOT_REQUIRED) LastChange lastChange,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record ModuleSettings(
            @Schema(requiredMode = REQUIRED) List<String> modules) { }
    public record ClubPwa(@Schema(requiredMode = NOT_REQUIRED) String name, @Schema(requiredMode = NOT_REQUIRED) String shortName, @Schema(requiredMode = NOT_REQUIRED) Map<String, String> iconUrls) { }
    public record PostalTown(
            @Schema(requiredMode = REQUIRED) String town,
            @Schema(requiredMode = REQUIRED) String region) { }
    public record ParameterUpdate(
            @Schema(requiredMode = REQUIRED) Object value,
            @Schema(requiredMode = NOT_REQUIRED) String reason,
            @Schema(requiredMode = NOT_REQUIRED) String scopeRef,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record ModuleUpdate(
            @Schema(requiredMode = REQUIRED) @NotNull Boolean enabled) { }
    public record LocalTimeRange(
            @Schema(requiredMode = REQUIRED) @NotBlank @Pattern(regexp = "[0-2][0-9]:[0-5][0-9]") String open,
            @Schema(requiredMode = REQUIRED) @NotBlank @Pattern(regexp = "[0-2][0-9]:[0-5][0-9]") String close) { }
    @Schema(description = "Alias of club.openingHours: day keys and intervals use the parameter catalog value.")
    public record OpeningHoursUpdate(
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, LocalTimeRange> value,
            @Schema(requiredMode = NOT_REQUIRED) String reason,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    @Schema(description = "Alias of club.holidays.")
    public record HolidaysUpdate(
            @Schema(requiredMode = REQUIRED) @NotNull List<Holiday> value,
            @Schema(requiredMode = NOT_REQUIRED) String reason,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }

    public record Holiday(@Schema(requiredMode = REQUIRED) LocalDate date,
            @Schema(requiredMode = REQUIRED) String label) { }
    @Schema(description = "Versioned edit of club identity, contact and theme. Omitted fields are preserved. Console fields are rejected with PLATFORM_ONLY; a timeZone change with existing classes returns TIMEZONE_CHANGE_BLOCKED.")
    public record ClubUpdate(
            @Schema(requiredMode = REQUIRED) @NotNull Long version,
            @Schema(requiredMode = NOT_REQUIRED) String name,
            @Schema(requiredMode = NOT_REQUIRED) String legalName,
            @Schema(requiredMode = NOT_REQUIRED) String taxId,
            @Schema(requiredMode = NOT_REQUIRED) ClubAddress address,
            @Schema(requiredMode = NOT_REQUIRED) String contactEmail,
            @Schema(requiredMode = NOT_REQUIRED) String contactPhone,
            @Schema(requiredMode = NOT_REQUIRED) String websiteUrl,
            @Schema(requiredMode = NOT_REQUIRED) com.agilityhub.core.platform.domain.Theme theme,
            @Schema(requiredMode = NOT_REQUIRED, description = "Console-only field; cannot be changed here.") String timeZone) { }
    public record CountryProfileSettings(
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) List<String> idDocumentTypes,
            @Schema(requiredMode = REQUIRED) boolean postalCodeLookup,
            @Schema(requiredMode = REQUIRED) String phonePrefix,
            @Schema(requiredMode = REQUIRED) String dateFormat,
            @Schema(requiredMode = REQUIRED) String timeFormat) { }

}
