package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.domain.ClubModule;
import com.agilityhub.core.platform.domain.CountryProfile;
import com.agilityhub.core.platform.domain.ImmutableValues;
import com.agilityhub.core.platform.domain.Pwa;
import com.agilityhub.core.platform.domain.Theme;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ClubConfig(ClubView club, Map<String, Object> parameters, Set<ClubModule> modules,
                         CountryProfile countryProfile, Map<String, Map<String, Object>> scopedParameters) {
    public ClubConfig {
        parameters = ImmutableValues.map(parameters); modules = Set.copyOf(modules);
        var copy = new java.util.LinkedHashMap<String, Map<String, Object>>();
        scopedParameters.forEach((scope, values) -> copy.put(scope, ImmutableValues.map(values)));
        scopedParameters = Map.copyOf(copy);
    }
    public <T> T get(String key, Class<T> type) { return get(key, null, type); }
    public <T> T get(String key, String scopeRef, Class<T> type) {
        if (!parameters.containsKey(key)) { throw new ApiException(ErrorCode.UNKNOWN_PARAMETER); }
        Object value = scopeRef == null ? parameters.get(key)
                : scopedParameters.getOrDefault(scopeRef, Map.of()).getOrDefault(key, parameters.get(key));
        if (value == null) { return null; }
        if (type == Integer.class && value instanceof Number number) { return type.cast(Math.toIntExact(number.longValue())); }
        if (type == Money.class && value instanceof Map<?, ?> money) {
            return type.cast(new Money(((Number) money.get("amountMinor")).longValue(), (String) money.get("currency")));
        }
        return type.cast(value);
    }
    public record ClubView(String id, String slug, String name, List<String> locales, String defaultLocale,
                           String timeZone, String currency, Theme theme, Pwa pwa, String status, String privacyPolicyUrl) {
        public ClubView { locales = List.copyOf(locales); }
    }
}
