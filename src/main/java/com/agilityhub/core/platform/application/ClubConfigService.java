package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.domain.ParameterValidator;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.ParameterRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TimeZoneProvider;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ClubConfigService implements TimeZoneProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ClubConfigService.class);
    private final ClubRepository clubs;
    private final ParameterRepository parameters;
    private final ParameterCatalog catalog;
    private final CountryProfileRegistry countries;
    private final ParameterValidator validator = new ParameterValidator();
    private final com.agilityhub.core.shared.application.CacheLoads<String, ClubConfig> cache = com.agilityhub.core.shared.application.CacheLoads.of(
            Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(Duration.ofMinutes(5)).build());
    public ClubConfigService(ClubRepository clubs, ParameterRepository parameters, ParameterCatalog catalog, CountryProfileRegistry countries) {
        this.clubs = clubs; this.parameters = parameters; this.catalog = catalog; this.countries = countries;
    }
    public java.util.List<String> activeClubIds() { return clubs.activeClubs().stream().map(c -> c.id()).toList(); }
    public java.util.Optional<String> findClubIdBySlug(String slug) { return clubs.findBySlug(slug).map(club -> club.id()); }
    public ClubConfig get(String clubId) {
        try (var scope = TenantContext.open(clubId)) {
            // Declarative applies validate dependent catalogs against the same transaction's club and parameters.
            // Uncommitted configuration must never enter the shared cache.
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) { return load(clubId); }
            return cache.get(clubId, this::load);
        }
    }
    /** The committed club and parameters, never the shared cache: for decisions that must follow a change at once (E3-T09, `SIGNUP_CLOSED`). */
    public ClubConfig current(String clubId) {
        try (var scope = TenantContext.open(clubId)) { return load(clubId); }
    }
    public void invalidate(String clubId) { cache.invalidate(clubId); }
    public void invalidateAfterCommit(String clubId) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { invalidate(clubId); }
                });
    }
    /** Consent writes must check the current version, independently of the branding cache. */
    public PrivacyPolicy privacyPolicy(String clubId) {
        var legal = clubs.findById(clubId).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND)).legal();
        return new PrivacyPolicy(legal.legalTextsVersion(), legal.privacyPolicyUrl());
    }
    public record PrivacyPolicy(String version, String url) { }
    @Override public ZoneId timeZone(String clubId) { return ZoneId.of(get(clubId).club().timeZone()); }
    private ClubConfig load(String clubId) {
        var club = clubs.findById(clubId).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
        Map<String, Object> values = new LinkedHashMap<>();
        catalog.entries().forEach((key, definition) -> {
            Object value = definition.defaultValue();
            if (definition.type().equals("money") && value instanceof Map<?, ?> money) {
                value = Map.of("amountMinor", money.get("amountMinor"), "currency", club.currency());
            }
            values.put(key, value);
        });
        Map<String, Map<String, Object>> scoped = new LinkedHashMap<>();
        for (var override : parameters.findAll()) {
            // A reset retains the document's version and history but has no active override.
            if (override.value() == null) { continue; }
            try {
                var definition = catalog.get(override.key());
                if (override.scopeRef() != null && !definition.scope().equals("ring") && !definition.scope().equals("level")) {
                    throw new ApiException(ErrorCode.PARAMETER_INVALID);
                }
                validator.validate(definition, override.value(), club.defaultLocale(), club.currency());
                if (override.scopeRef() == null) { values.put(override.key(), override.value()); }
                else { scoped.computeIfAbsent(override.scopeRef(), ignored -> new LinkedHashMap<>()).put(override.key(), override.value()); }
            } catch (ApiException invalid) {
                // Do not log the value: stored overrides can contain private configuration.
                LOG.warn("ParameterInvalidOverride clubId={} key={} error={}", clubId, override.key(), invalid.code());
            }
        }
        var view = new ClubConfig.ClubView(club.id(), club.slug(), club.name(), club.locales(), club.defaultLocale(),
                club.timeZone(), club.currency(), club.theme(), club.pwa(), club.status().name(), club.legal().privacyPolicyUrl(), club.address() == null ? null : club.address().city(),
                club.legalName(), club.taxId());
        return new ClubConfig(view, values, club.modules(), countries.get(club.countryProfile()), scoped);
    }
}
