package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.Comparator;
import org.springframework.stereotype.Service;

@Service
public class ClubAppUrls {
    private final ClubRepository clubs;
    public ClubAppUrls(ClubRepository clubs) { this.clubs = clubs; }
    public String login(String clientId) {
        String app = switch (clientId) {
            case "clubs-app" -> "clubs";
            case "clubs-admin" -> "clubs-admin";
            default -> throw new ApiException(ErrorCode.NO_MEMBERSHIP);
        };
        var club = clubs.findById(TenantContext.require()).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
        if (club.status() == Club.Status.SUSPENDED) { throw new ApiException(ErrorCode.CLUB_SUSPENDED); }
        var domain = club.domains().stream().filter(d -> app.equals(d.app()) && d.status() == Club.DomainStatus.VERIFIED)
                .sorted(Comparator.comparing(Club.Domain::primary).reversed().thenComparing(Club.Domain::host))
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_HOST));
        return "https://" + domain.host() + "/entrar";
    }
}
