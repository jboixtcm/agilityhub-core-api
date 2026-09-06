package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.domain.Theme;
import java.util.List;

/** Explicit public allowlist: never serialize the persistence Club or resolved parameter map. */
public record BrandingResponse(ClubSummary club, Theme theme, List<String> locales, String defaultLocale,
                               String timeZone, String currency, Country countryProfile, List<String> modules,
                               Signup signup, String status, Legal legal) {
    public static BrandingResponse from(ClubConfig config) {
        var club = config.club(); var profile = config.countryProfile();
        return new BrandingResponse(new ClubSummary(club.slug(), club.name(), club.city()), club.theme(), club.locales(), club.defaultLocale(),
                club.timeZone(), club.currency(), new Country(profile.code(), profile.idDocumentTypes(), profile.defaultPhonePrefix()),
                config.modules().stream().map(Enum::name).sorted().toList(), new Signup(Boolean.TRUE.equals(config.get("signup.enabled", Boolean.class))),
                club.status(), new Legal(club.privacyPolicyUrl()));
    }
    public record ClubSummary(String slug, String name, @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"}) String city) { }
    public record Country(String code, List<String> idDocumentTypes, String phonePrefix) { }
    public record Signup(boolean enabled) { }
    public record Legal(String privacyPolicyUrl) { }
}
