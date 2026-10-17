package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.domain.CountryProfile;
import com.agilityhub.core.platform.domain.ImmutableValues;
import com.agilityhub.core.platform.domain.Pwa;
import com.agilityhub.core.platform.domain.Theme;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** Persistence representation follows MODEL_DADES_PLATAFORMA §2. Never serialize as an API response. */
@Document("clubs")
public record Club(@Id String id, String slug, String name, String legalName, String taxId,
                   Address address, String displayCity, String contactEmail, String contactPhone, String websiteUrl,
                   List<String> locales, String defaultLocale, String timeZone, String currency,
                   String countryProfile, List<Domain> domains, Theme theme, Pwa pwa,
                   Set<Module> modules, Map<String, Object> paymentProviders, Legal legal,
                   Status status, Map<String, Boolean> onboardingChecklist, Map<String, Long> usage,
                   @Version Long version, Instant createdAt, Instant updatedAt, Boolean template, String publicApiKeyHash, Billing billing, String notes,
                   String publicApiKeyPreviousHash, Instant publicApiKeyPreviousUntil) {
    /**
     * Before E10-T04: no rotated public key. S17 R-17-11: a rotation keeps the replaced key's digest valid until
     * `publicApiKeyPreviousUntil` (24 h of overlap), then only `publicApiKeyHash` opens the public routes.
     */
    public Club(String id, String slug, String name, String legalName, String taxId, Address address, String displayCity,
                String contactEmail, String contactPhone, String websiteUrl, List<String> locales, String defaultLocale,
                String timeZone, String currency, String countryProfile, List<Domain> domains, Theme theme, Pwa pwa,
                Set<Module> modules, Map<String, Object> paymentProviders, Legal legal, Status status,
                Map<String, Boolean> onboardingChecklist, Map<String, Long> usage, Long version, Instant createdAt, Instant updatedAt,
                Boolean template, String publicApiKeyHash, Billing billing, String notes) {
        this(id, slug, name, legalName, taxId, address, displayCity, contactEmail, contactPhone, websiteUrl, locales, defaultLocale,
                timeZone, currency, countryProfile, domains, theme, pwa, modules, paymentProviders, legal, status,
                onboardingChecklist, usage, version, createdAt, updatedAt, template, publicApiKeyHash, billing, notes, null, null);
    }
    /** Compatibility for callers that do not set internal platform notes. */
    public Club(String id, String slug, String name, String legalName, String taxId, Address address, String displayCity,
                String contactEmail, String contactPhone, String websiteUrl, List<String> locales, String defaultLocale,
                String timeZone, String currency, String countryProfile, List<Domain> domains, Theme theme, Pwa pwa,
                Set<Module> modules, Map<String, Object> paymentProviders, Legal legal, Status status,
                Map<String, Boolean> onboardingChecklist, Map<String, Long> usage, Long version, Instant createdAt, Instant updatedAt,
                Boolean template, String publicApiKeyHash, Billing billing) {
        this(id, slug, name, legalName, taxId, address, displayCity, contactEmail, contactPhone, websiteUrl, locales, defaultLocale,
                timeZone, currency, countryProfile, domains, theme, pwa, modules, paymentProviders, legal, status,
                onboardingChecklist, usage, version, createdAt, updatedAt, template, publicApiKeyHash, billing, null);
    }
    public Club(String id, String slug, String name, String legalName, String taxId, Address address,
                String contactEmail, String contactPhone, String websiteUrl, List<String> locales, String defaultLocale,
                String timeZone, String currency, String countryProfile, List<Domain> domains, Theme theme, Pwa pwa,
                Set<Module> modules, Map<String, Object> paymentProviders, Legal legal, Status status,
                Map<String, Boolean> onboardingChecklist, Map<String, Long> usage, Long version, Instant createdAt, Instant updatedAt) {
        this(id, slug, name, legalName, taxId, address, contactEmail, contactPhone, websiteUrl, locales, defaultLocale,
                timeZone, currency, countryProfile, domains, theme, pwa, modules, paymentProviders, legal, status,
                onboardingChecklist, usage, version, createdAt, updatedAt, false);
    }
    public Club(String id, String slug, String name, String legalName, String taxId, Address address,
                String contactEmail, String contactPhone, String websiteUrl, List<String> locales, String defaultLocale,
                String timeZone, String currency, String countryProfile, List<Domain> domains, Theme theme, Pwa pwa,
                Set<Module> modules, Map<String, Object> paymentProviders, Legal legal, Status status,
                Map<String, Boolean> onboardingChecklist, Map<String, Long> usage, Long version, Instant createdAt, Instant updatedAt, Boolean template) {
        this(id, slug, name, legalName, taxId, address, null, contactEmail, contactPhone, websiteUrl, locales, defaultLocale,
                timeZone, currency, countryProfile, domains, theme, pwa, modules, paymentProviders, legal, status,
                onboardingChecklist, usage, version, createdAt, updatedAt, template, null, null);
    }
    /** The club before E8-T01 (no `billing` block yet). */
    public Club(String id, String slug, String name, String legalName, String taxId, Address address, String displayCity,
                String contactEmail, String contactPhone, String websiteUrl, List<String> locales, String defaultLocale,
                String timeZone, String currency, String countryProfile, List<Domain> domains, Theme theme, Pwa pwa,
                Set<Module> modules, Map<String, Object> paymentProviders, Legal legal, Status status,
                Map<String, Boolean> onboardingChecklist, Map<String, Long> usage, Long version, Instant createdAt, Instant updatedAt,
                Boolean template, String publicApiKeyHash) {
        this(id, slug, name, legalName, taxId, address, displayCity, contactEmail, contactPhone, websiteUrl, locales, defaultLocale,
                timeZone, currency, countryProfile, domains, theme, pwa, modules, paymentProviders, legal, status,
                onboardingChecklist, usage, version, createdAt, updatedAt, template, publicApiKeyHash, null);
    }
    public Club {
        template = Boolean.TRUE.equals(template);
        // S02 §3, R-02-06 (E5-T16): the tax id is kept normalized, so every writer stores it, and every reader compares and
        // publishes it, in one form; a value stored before the rule is read in that form too.
        taxId = CountryProfile.normalizeTaxId(taxId);
        if (slug == null || !slug.matches("[a-z0-9-]{3,40}") || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Invalid club identity");
        }
        locales = List.copyOf(locales);
        if (!Set.of("ca", "es", "en").containsAll(locales) || !locales.contains(defaultLocale)) {
            throw new IllegalArgumentException("Invalid club locales");
        }
        ZoneId.of(timeZone); Currency.getInstance(currency);
        if (!Set.of("ES", "GENERIC").contains(countryProfile)) { throw new IllegalArgumentException("Invalid country profile"); }
        domains = List.copyOf(domains); modules = Set.copyOf(modules);
        paymentProviders = ImmutableValues.map(paymentProviders);
        onboardingChecklist = Map.copyOf(onboardingChecklist); usage = Map.copyOf(usage);
    }
    public record Address(String street, String postalCode, String city, String region, String country) { }
    /**
     * S12 §3 / model Annex B (E8-T01): the receipt numbering, outside `SEPA_XML` because a club without SEPA numbers too.
     * `nextNumber` is taken atomically (`findOneAndUpdate` on `clubs`) inside the run's transaction and given back by a rollback
     * (R-12-08, R-12-14); the pattern and the yearly reset follow `billing.invoiceSeriesPattern` / `billing.invoiceResetYearly`.
     * Null until the first invoice. Every full save of the club carries it unchanged, so no other writer drops the counter.
     * E8-T02 (R-12-08): `counters` holds the next number of each counter key — the resolved series when numbering resets
     * yearly («no counter for the new series yet» = 1), one running key otherwise; `nextNumber` (an imported Playoff
     * counter, S18) seeds the first key only. Written only by `InvoiceCounters`, which bumps the club's version.
     */
    public record Billing(String invoiceSeriesPattern, Long nextNumber, Boolean resetYearly, Map<String, Long> counters) {
        public Billing(String invoiceSeriesPattern, Long nextNumber, Boolean resetYearly) { this(invoiceSeriesPattern, nextNumber, resetYearly, null); }
    }
    /**
     * S17 R-17-03 (E10-T04, model proposal E112 approved in E114): `verificationToken` is the TXT challenge
     * (`_agilityhub-verify.{host}` = `agh-verify-{token}`), set when a domain is added, never changed while `PENDING` and
     * removed when it is `VERIFIED`. It is published in DNS, so it is not a secret. Null for a domain without a challenge.
     * <p>
     * `checkedAt` and `failedChecks` (E10-T03, model proposal in its report): the last periodic re-check of S15 P9 and the
     * failed re-checks in a row since the last good one (R-17-03: three make a `VERIFIED` domain `BROKEN`). Null before the
     * first re-check; a verification resets them.
     */
    public record Domain(String host, String app, DomainStatus status, Instant verifiedAt, boolean primary, String verificationToken,
            Instant checkedAt, Integer failedChecks) {
        public Domain { host = com.agilityhub.core.platform.domain.HostNames.normalize(host); }
        public Domain(String host, String app, DomainStatus status, Instant verifiedAt, boolean primary, String verificationToken) {
            this(host, app, status, verifiedAt, primary, verificationToken, null, null);
        }
        public Domain(String host, String app, DomainStatus status, Instant verifiedAt, boolean primary) {
            this(host, app, status, verifiedAt, primary, null);
        }
        /** The failed re-checks in a row (0 before the first one). */
        public int failures() { return failedChecks == null ? 0 : failedChecks; }
        /** The same domain as primary or not, everything else kept. */
        public Domain withPrimary(boolean value) { return new Domain(host, app, status, verifiedAt, value, verificationToken, checkedAt, failedChecks); }
        /** The same domain with the re-check state of `stored` (E10-T03 round 2). */
        public Domain withChecksOf(Domain stored) { return new Domain(host, app, status, verifiedAt, primary, verificationToken, stored.checkedAt, stored.failedChecks); }
    }
    /** The same club with other domains, everything else kept (E10-T03 round 2: a full save keeps the re-check state). */
    public Club withDomains(List<Domain> value) {
        return new Club(id, slug, name, legalName, taxId, address, displayCity, contactEmail, contactPhone, websiteUrl, locales, defaultLocale,
                timeZone, currency, countryProfile, value, theme, pwa, modules, paymentProviders, legal, status, onboardingChecklist, usage,
                version, createdAt, updatedAt, template, publicApiKeyHash, billing, notes, publicApiKeyPreviousHash, publicApiKeyPreviousUntil);
    }
    public record Legal(String privacyPolicyUrl, Map<String, String> imageConsentText, String legalTextsVersion) {
        public Legal { imageConsentText = Map.copyOf(imageConsentText); }
    }
    /** MODEL_DADES_PLATAFORMA Annex B; `BROKEN` is S17 R-17-03's weekly re-check that failed three times. */
    public enum DomainStatus { PENDING, VERIFIED, BROKEN }
    public enum Status { ONBOARDING, ACTIVE, SUSPENDED }
}
