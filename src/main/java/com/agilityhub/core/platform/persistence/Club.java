package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.platform.application.Module;
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
                   Address address, String contactEmail, String contactPhone, String websiteUrl,
                   List<String> locales, String defaultLocale, String timeZone, String currency,
                   String countryProfile, List<Domain> domains, Theme theme, Pwa pwa,
                   Set<Module> modules, Map<String, Object> paymentProviders, Legal legal,
                   Status status, Map<String, Boolean> onboardingChecklist, Map<String, Long> usage,
                   @Version Long version, Instant createdAt, Instant updatedAt) {
    public Club {
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
    public record Domain(String host, String app, DomainStatus status, Instant verifiedAt, boolean primary) {
        public Domain { host = com.agilityhub.core.platform.domain.HostNames.normalize(host); }
    }
    public record Legal(String privacyPolicyUrl, Map<String, String> imageConsentText, String legalTextsVersion) {
        public Legal { imageConsentText = Map.copyOf(imageConsentText); }
    }
    public enum DomainStatus { PENDING, VERIFIED }
    public enum Status { ONBOARDING, ACTIVE, SUSPENDED }
}
