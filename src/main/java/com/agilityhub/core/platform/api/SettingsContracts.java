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
            String scopeRef,
            LastChange lastChange,
            @Schema(requiredMode = REQUIRED) ParameterEditor editableBy,
            @Schema(requiredMode = REQUIRED) Map<String, Object> constraints,
            String module,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record ParameterHistoryEntry(
            @Schema(requiredMode = REQUIRED) Object value,
            @Schema(requiredMode = REQUIRED) Instant changedAt,
            @Schema(requiredMode = REQUIRED, format = "uuid") String changedByAccountId,
            String reason) { }
    public record ParameterBlock(
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED) String title,
            @Schema(requiredMode = REQUIRED) List<Parameter> rows) { }
    public record Parameters(
            @Schema(requiredMode = REQUIRED) List<ParameterBlock> blocks,
            LastChange lastChange) { }
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
            String street,
            String postalCode,
            String city,
            String region,
            String country) { }
    public record ClubDomain(
            @Schema(requiredMode = REQUIRED) String host,
            @Schema(requiredMode = REQUIRED) String app,
            Instant verifiedAt,
            @Schema(requiredMode = REQUIRED) boolean primary) { }
    public record ClubLegal(
            @Schema(requiredMode = REQUIRED) String privacyPolicyUrl,
            String imageConsentText,
            Map<String, String> imageConsentTextI18n,
            @Schema(requiredMode = REQUIRED) String legalTextsVersion) { }
    @Schema(description = "Configuration status only; provider credentials and API key hashes are never exposed.")
    public record PaymentProviderSummary(
            @Schema(requiredMode = REQUIRED) boolean configured,
            @Schema(requiredMode = REQUIRED) boolean enabled) { }
    public record ClubSettings(
            @Schema(requiredMode = REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = REQUIRED) String slug,
            @Schema(requiredMode = REQUIRED) String name,
            String legalName,
            @Schema(format = "uuid") String taxId,
            ClubAddress address,
            String contactEmail,
            String contactPhone,
            String websiteUrl,
            @Schema(requiredMode = REQUIRED) List<String> locales,
            @Schema(requiredMode = REQUIRED) String defaultLocale,
            @Schema(requiredMode = REQUIRED) String timeZone,
            @Schema(requiredMode = REQUIRED) String currency,
            @Schema(requiredMode = REQUIRED) String countryProfile,
            @Schema(requiredMode = REQUIRED) List<ClubDomain> domains,
            @Schema(requiredMode = REQUIRED) com.agilityhub.core.platform.domain.Theme theme,
            ClubPwa pwa,
            @Schema(requiredMode = REQUIRED) List<String> modules,
            Map<String, PaymentProviderSummary> paymentProviders,
            ClubLegal legal,
            @Schema(requiredMode = REQUIRED) String status,
            LastChange lastChange,
            @Schema(requiredMode = REQUIRED) long version) { }
    public record ModuleSettings(
            @Schema(requiredMode = REQUIRED) List<String> modules) { }
    public record ClubPwa(String name, String shortName, Map<String, String> iconUrls) { }
    public record PostalTown(
            @Schema(requiredMode = REQUIRED) String town,
            @Schema(requiredMode = REQUIRED) String region) { }
    public record ParameterUpdate(
            @Schema(requiredMode = REQUIRED) Object value,
            String reason,
            String scopeRef,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    public record ModuleUpdate(
            @Schema(requiredMode = REQUIRED) @NotNull Boolean enabled) { }
    public record LocalTimeRange(
            @Schema(requiredMode = REQUIRED) @NotBlank @Pattern(regexp = "[0-2][0-9]:[0-5][0-9]") String open,
            @Schema(requiredMode = REQUIRED) @NotBlank @Pattern(regexp = "[0-2][0-9]:[0-5][0-9]") String close) { }
    @Schema(description = "Alias of club.openingHours: day keys and intervals use the parameter catalog value.")
    public record OpeningHoursUpdate(
            @Schema(requiredMode = REQUIRED) @NotNull Map<String, LocalTimeRange> value,
            String reason,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }
    @Schema(description = "Alias of club.holidays.")
    public record HolidaysUpdate(
            @Schema(requiredMode = REQUIRED) @NotNull List<LocalDate> value,
            String reason,
            @Schema(requiredMode = REQUIRED) @NotNull Long version) { }

}
