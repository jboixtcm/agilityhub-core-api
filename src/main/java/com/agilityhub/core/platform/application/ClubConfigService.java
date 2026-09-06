package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.domain.ParameterValidator;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.ParameterRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TimeZoneProvider;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.github.benmanes.caffeine.cache.Cache;
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
    private final Cache<String, ClubConfig> cache = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(5)).build();
    public ClubConfigService(ClubRepository clubs, ParameterRepository parameters, ParameterCatalog catalog, CountryProfileRegistry countries) {
        this.clubs = clubs; this.parameters = parameters; this.catalog = catalog; this.countries = countries;
    }
    public java.util.Optional<String> findClubIdBySlug(String slug) { return clubs.findBySlug(slug).map(club -> club.id()); }
    public ClubConfig get(String clubId) {
        try (var scope = TenantContext.open(clubId)) { return cache.get(clubId, this::load); }
    }
    public void invalidate(String clubId) { cache.invalidate(clubId); }
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
                club.timeZone(), club.currency(), club.theme(), club.pwa(), club.status().name(), club.legal().privacyPolicyUrl(), club.address() == null ? null : club.address().city());
        return new ClubConfig(view, values, club.modules(), countries.get(club.countryProfile()), scoped);
    }
}
