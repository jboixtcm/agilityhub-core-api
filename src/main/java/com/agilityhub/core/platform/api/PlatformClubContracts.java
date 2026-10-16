package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.domain.Theme;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.AccessMode.WRITE_ONLY;

/** S17 console projections: configuration and aggregate usage only; never a persistence Club. */
public final class PlatformClubContracts {
    private PlatformClubContracts() { }
    public enum ClubStatus { ONBOARDING, ACTIVE, SUSPENDED }
    public enum DomainStatus { PENDING, VERIFIED, BROKEN }
    public enum DomainApp { @com.fasterxml.jackson.annotation.JsonProperty("clubs") CLUBS, @com.fasterxml.jackson.annotation.JsonProperty("clubs-admin") CLUBS_ADMIN }
    public enum ModulePreset { COMPLET, MINIM, CANIC }
    public enum Provider { SEPA_XML, STRIPE, MANUAL }
    public enum StripeMode { @com.fasterxml.jackson.annotation.JsonProperty("test") TEST, @com.fasterxml.jackson.annotation.JsonProperty("live") LIVE }
    public enum CatalogPart { LEVELS, RINGS, PLANS, FAQ, MESSAGE_TEMPLATES }
    public enum VerificationMethod { CNAME, TXT }
    @Schema(description="S17 §2 DNS instructions (R-17-03): a CNAME from the host to cnameTarget, or the TXT record recordName = recordValue (_agilityhub-verify.{host} = agh-verify-{token}). The token is generated when the domain is added, stays stable while it is PENDING and is removed once it is VERIFIED.")
    public record DomainVerificationChallenge(String recordName, String recordValue,
            @Schema(description="The product host of the domain's app the CNAME points to: clubs.agilitydoghub.com for clubs, clubsadmin.agilitydoghub.com for clubs-admin (deployment hosts).") String cnameTarget) { }
    public record PlatformDomain(String host, DomainApp app, DomainStatus status,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) Instant verifiedAt, boolean primary,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true, description="The DNS instructions while the domain is not verified; null once it is VERIFIED and for a legacy domain without a challenge.") DomainVerificationChallenge verification) { }
    public record OnboardingChecklist(boolean dns,
            @Schema(description="At least one configured payment provider when BILLING is enabled; legacy model key.") boolean sepa,
            boolean branding, boolean templates, boolean faq, boolean legal, boolean firstAdmin) { }
    public record PlatformClubLegal(@NotBlank String privacyPolicyUrl, @NotNull Map<String,String> imageConsentText, @NotBlank String legalTextsVersion) { }
    public record PlatformClubUsage(String month, long membersActive, long smsSentMonth, long storageBytes) { }
    public record PlatformProviderSummary(boolean enabled, boolean configured,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String masked,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true, description="Last four characters of the publishable key only.") String last4,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) StripeMode mode,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) Instant lastTestAt,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true, description="SEPA_XML creditor name; null for other providers.") String creditorName,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true, description="SEPA_XML creditor identifier; null for other providers.") String creditorId,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true, description="SEPA_XML creditor suffix; null for other providers.") String suffix,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true, description="SEPA_XML masked IBAN only; never a full IBAN.") String ibanMasked,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true, description="MANUAL instructions in the target club's locales; null for other providers.") Map<String,String> instructions,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true, description="STRIPE masked account identifier only; null when unavailable or for other providers.") String accountIdMasked) { }
    public record PlatformClub(String id, String slug, String name,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String legalName,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String taxId,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) SettingsContracts.ClubAddress address,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String displayCity,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String contactEmail,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String contactPhone,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String websiteUrl,
            List<String> locales, String defaultLocale, String timeZone, String currency, String countryProfile,
            List<PlatformDomain> domains, Theme theme, Set<Module> modules,
            Map<Provider,PlatformProviderSummary> paymentProviders,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) @Valid PlatformClubLegal legal,
            ClubStatus status, boolean template,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String notes,
            OnboardingChecklist onboardingChecklist, PlatformClubUsage usage, long version,
            Instant createdAt, Instant updatedAt) { }
    public record PlatformClubListItem(String id,
            @Schema(requiredMode=NOT_REQUIRED) String name,
            @Schema(requiredMode=NOT_REQUIRED) String slug,
            @Schema(requiredMode=NOT_REQUIRED) ClubStatus status,
            @Schema(requiredMode=NOT_REQUIRED) String countryProfile,
            @Schema(requiredMode=NOT_REQUIRED) String timeZone,
            @Schema(requiredMode=NOT_REQUIRED) Set<Module> modules,
            @Schema(requiredMode=NOT_REQUIRED) boolean template,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) Instant lastAdminAccessAt,
            @Schema(requiredMode=NOT_REQUIRED) PlatformClubUsage usage,
            @Schema(requiredMode=NOT_REQUIRED) OnboardingChecklist onboardingChecklist) { }
    public record PlatformClubPage(List<PlatformClubListItem> items, int page, int size, long totalItems, int totalPages, List<com.agilityhub.core.shared.application.contract.ApiContracts.Filter> appliedFilters) { }
    public record PlatformClubCreated(PlatformClub club, OnboardingChecklist checklist) { }
    public record PlatformClubPatch(@NotNull @PositiveOrZero Long version,
            @Schema(requiredMode=NOT_REQUIRED) @Size(min=1) String name,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String legalName,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String taxId,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) @Valid SettingsContracts.ClubAddress address,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String displayCity,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) @Email String contactEmail,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String contactPhone,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String websiteUrl,
            @Schema(requiredMode=NOT_REQUIRED) @Size(min=1) List<@Pattern(regexp="ca|es|en") String> locales,
            @Schema(requiredMode=NOT_REQUIRED) @Pattern(regexp="ca|es|en") String defaultLocale,
            @Schema(requiredMode=NOT_REQUIRED) @Size(min=1) String timeZone,
            @Schema(requiredMode=NOT_REQUIRED) @Pattern(regexp="[A-Z]{3}") String currency,
            @Schema(requiredMode=NOT_REQUIRED) @Pattern(regexp="ES|GENERIC") String countryProfile,
            @Schema(requiredMode=NOT_REQUIRED, description="Omit to keep the current texts; null is rejected with 400 VALIDATION_ERROR {field: legal}.") @Valid PlatformClubLegal legal,
            @Schema(requiredMode=NOT_REQUIRED) Boolean template,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) String notes) { }
    public record PlatformDomainRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9.-]+") String host, @NotNull DomainApp app) { }
    public record DomainVerification(DomainStatus status, Instant checkedAt, VerificationMethod method) { }
    public record PlatformThemeRequest(@NotNull @PositiveOrZero Long version,
            @NotNull @Valid Theme.Colors colors, @NotBlank String fontFamily, @NotBlank String radius,
            @NotNull List<String> ringPalette, @NotNull Theme.Mode mode,
            @Schema(requiredMode=NOT_REQUIRED, description="Light logo: fileKey from POST /platform/clubs/{id}/theme/upload-url, uploaded and not yet published elsewhere; empty removes it, omitted keeps it. Never a local path.") String logoFileKey,
            @Schema(requiredMode=NOT_REQUIRED, description="Dark logo: fileKey from POST /platform/clubs/{id}/theme/upload-url; empty removes it, omitted keeps it.") String logoDarkFileKey,
            @Schema(requiredMode=NOT_REQUIRED, description="Mark: fileKey from POST /platform/clubs/{id}/theme/upload-url; empty removes it, omitted keeps it.") String markFileKey) { }
    public record PlatformModulesRequest(@NotNull @PositiveOrZero Long version,
            @Schema(requiredMode=NOT_REQUIRED) Set<@NotNull Module> modules,
            @Schema(requiredMode=NOT_REQUIRED) ModulePreset preset) {
        @com.fasterxml.jackson.annotation.JsonIgnore @AssertTrue(message="Choose modules or preset")
        public boolean isSelectionValid() { return (modules==null)!=(preset==null); }
    }
    public record PlatformModules(Set<Module> modules, long version) { }
    @Schema(description="Provider-specific configuration. STRIPE requires publishableKey and mode; SEPA_XML requires creditorName, creditorId, iban and suffix; MANUAL requires instructions. Secrets are write-only. Omitted Stripe secrets preserve stored values.")
    public record PlatformProviderRequest(@NotNull @PositiveOrZero Long version,
            @Schema(requiredMode=NOT_REQUIRED) Boolean enabled,
            @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
            @Schema(requiredMode=NOT_REQUIRED, accessMode=WRITE_ONLY) String secretKey,
            @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
            @Schema(requiredMode=NOT_REQUIRED, accessMode=WRITE_ONLY) String webhookSecret,
            @Schema(requiredMode=NOT_REQUIRED) String publishableKey,
            @Schema(requiredMode=NOT_REQUIRED) StripeMode mode,
            @Schema(requiredMode=NOT_REQUIRED) String creditorName,
            @Schema(requiredMode=NOT_REQUIRED) String creditorId,
            @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
            @Schema(requiredMode=NOT_REQUIRED, accessMode=WRITE_ONLY) String iban,
            @Schema(requiredMode=NOT_REQUIRED) String suffix,
            @Schema(requiredMode=NOT_REQUIRED) Map<String,String> instructions) {
        @Override public String toString() { return "PlatformProviderRequest[redacted]"; }
    }
    public record ProviderConnectionTest(boolean ok, String accountName, StripeMode mode) { }
    public record CatalogCloneRequest(@NotBlank String fromClubId, @NotEmpty Set<@NotNull CatalogPart> parts) { }
    public record CatalogCloneResult(Map<CatalogPart,Integer> copied, List<CatalogCloneWarning> warnings) { }
    /** S17 R-17-08 (E118 #3): LOCALE_OMITTED (the source's `from` locale is not the destination's) or DEFAULT_LOCALE_COPIED (the source's `from` text filled the destination's default locale). */
    public enum CatalogCloneWarningCode { LOCALE_OMITTED, DEFAULT_LOCALE_COPIED }
    @Schema(description="S17 R-17-08: one localized-text adjustment of a cloned part, once per code, part and locale.")
    public record CatalogCloneWarning(CatalogCloneWarningCode code, CatalogPart part,
            @Schema(description="The source locale the warning is about (ca, es or en).") String from) { }
    public record PlatformAdmin(String accountId, String name, String email, String membershipStatus,
            @Schema(requiredMode=NOT_REQUIRED, nullable=true) Instant lastAccessAt) { }
    public record PlatformAdminRequest(@NotBlank @Email String email, @NotBlank String name) { }
    public record PlatformClubStatusRequest(@NotNull ClubStatus status,
            @Schema(requiredMode=NOT_REQUIRED) String reason) {
        @com.fasterxml.jackson.annotation.JsonIgnore @AssertTrue(message="Suspension requires a reason")
        public boolean isReasonValid() { return status!=ClubStatus.SUSPENDED || reason!=null&&!reason.isBlank(); }
    }
    public record SupportAccessRequest(@NotBlank String reason) { }
    public record SupportAccess(String token, Instant expiresAt) { }
    public record PublicApiKey(String apiKey) { }
    public record PlatformClubTemplate(String id, String name, List<String> locales, String currency) { }
}
