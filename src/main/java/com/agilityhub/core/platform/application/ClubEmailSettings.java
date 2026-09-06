package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ClubEmailSettings {
    private final ClubRepository clubs;
    private final ClubConfigService configs;
    public ClubEmailSettings(ClubRepository clubs, ClubConfigService configs) { this.clubs = clubs; this.configs = configs; }
    public Settings get(String clubId, String platformAddress) {
        var club = clubs.findById(clubId).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
        var config = configs.get(clubId);
        String address = config.get("messaging.email.fromAddress", String.class);
        String domain = domain(address);
        boolean verified = domain.equals(domain(platformAddress)) || club.domains().stream().anyMatch(candidate ->
                candidate.host().equals(domain) && candidate.status() == Club.DomainStatus.VERIFIED && candidate.verifiedAt() != null);
        String reply = config.get("messaging.email.replyTo", String.class);
        if (reply == null || reply.isBlank()) { reply = club.contactEmail(); }
        var theme = club.theme();
        return new Settings(club.name(), theme.logoUrl(), theme.colors().primary(), theme.colors().onPrimary(),
                verified ? address : platformAddress, config.get("messaging.email.fromName", String.class), reply,
                club.defaultLocale(), config.get("auth.magicLinkMinutes", Integer.class));
    }
    private String domain(String address) {
        if (address == null || !address.contains("@")) { return ""; }
        return address.substring(address.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
    }
    public record Settings(String brand, String logoUrl, String primary, String onPrimary, String fromAddress,
                           String fromName, String replyTo, String defaultLocale, int magicLinkMinutes) { }
}
