package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.IdentityService;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {
    private final IdentityService identities;
    private final ClubConfigService clubs;
    public MeController(IdentityService identities, ClubConfigService clubs) { this.identities = identities; this.clubs = clubs; }
    @GetMapping("/api/v1/me")
    @PreAuthorize("hasAnyRole('MEMBER', 'INSTRUCTOR', 'ADMIN', 'AGILITYHUB_ADMIN')")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        var session = identities.current(jwt.getSubject());
        var account = session.account();
        var membership = session.membership();
        return new MeResponse(new MeResponse.AccountDto(account.id(), account.name(), account.email(), account.locale()),
                new MeResponse.MembershipDto(membership.roles(), membership.memberId(), membership.defaultProfile()),
                clubs.get(TenantContext.require()).modules().stream().map(Enum::name).sorted().toList());
    }
}
