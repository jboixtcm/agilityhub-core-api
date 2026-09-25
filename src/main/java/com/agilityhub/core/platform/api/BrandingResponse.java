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
        var office = club.legalAddress();
        return new BrandingResponse(new ClubSummary(club.slug(), club.name(), club.city(), blankToNull(club.legalName()), blankToNull(club.taxId()),
                office == null ? null : new LegalAddress(office.street(), office.postalCode(), office.city())), club.theme(), club.locales(), club.defaultLocale(),
                club.timeZone(), club.currency(), new Country(profile.code(), profile.idDocumentTypes(), profile.defaultPhonePrefix()),
                config.modules().stream().map(Enum::name).sorted().toList(), new Signup(Boolean.TRUE.equals(config.get("signup.enabled", Boolean.class))),
                club.status(), new Legal(club.privacyPolicyUrl()));
    }
    /**
     * R-02-02 (amended 24-09): `legalName` and `taxId` are the club's public business identifiers, which LSSI art. 10 asks
     * the public web to show (the footer); always present, `null` when the club has not set them. E3-T16 (amended 25-09):
     * the footer reads «{legalName} · {taxId} · {city}», with the registered office «{street} · {postalCode} {city}» below.
     */
    public record ClubSummary(String slug, String name,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED,
                    types = {"string", "null"}, description = "The town shown with the club's name: displayCity, else the registered office's town.") String city,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED,
                    types = {"string", "null"}) String legalName,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED,
                    types = {"string", "null"}) String taxId,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED, types = {"object", "null"},
                    description = "The registered office (LSSI art. 10), or null when the club has no street or postal code.") LegalAddress legalAddress) { }
    public record LegalAddress(String street, String postalCode,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED,
                    types = {"string", "null"}) String city) { }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    public record Country(String code, List<String> idDocumentTypes, String phonePrefix) { }
    public record Signup(boolean enabled) { }
    public record Legal(String privacyPolicyUrl) { }
}
