package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Minimal census access to provider availability and the club application host; never credentials. */
@Service
public class CensusClubSettings {
    private final ClubRepository clubs;
    public CensusClubSettings(ClubRepository clubs) { this.clubs = clubs; }
    public int nextMemberNumber(int minimum) { return clubs.nextMemberNumber(minimum); }
    public boolean providerEnabled(String provider) {
        var raw = clubs.findById(TenantContext.require()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).paymentProviders().get(provider);
        return Boolean.TRUE.equals(raw) || (raw instanceof Map<?,?> settings && !Boolean.FALSE.equals(settings.get("enabled")));
    }
    public Map<String,Object> signupLegal(String locale) {
        var club = clubs.findById(TenantContext.require()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var legal = club.legal();
        return Map.of("privacyPolicyUrl", legal.privacyPolicyUrl(), "legalTextsVersion", legal.legalTextsVersion(),
                "imageConsentText", legal.imageConsentText().getOrDefault(locale, legal.imageConsentText().getOrDefault(club.defaultLocale(), "")),
                "legalName", club.legalName() == null ? club.name() : club.legalName());
    }
    public java.util.List<String> providers() {
        return clubs.findById(TenantContext.require()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).paymentProviders().keySet()
                .stream().filter(this::providerEnabled).toList();
    }
    public String appHost() {
        return clubs.findById(TenantContext.require()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).domains().stream().filter(domain -> "clubs".equals(domain.app())
                && "VERIFIED".equals(domain.status().name())).map(domain -> domain.host()).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE));
    }
}
