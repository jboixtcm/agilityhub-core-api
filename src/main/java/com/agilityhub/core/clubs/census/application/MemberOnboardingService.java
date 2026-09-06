package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.MemberIdentityRepository;
import com.agilityhub.core.shared.application.MemberOnboardingAccess;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class MemberOnboardingService implements MemberOnboardingAccess {
    private final MemberIdentityRepository members;
    public MemberOnboardingService(MemberIdentityRepository members) { this.members = members; }
    @Override public String phone(String memberId, String accountId) {
        return members.forAccount(memberId, accountId).map(member -> {
            if (member.phones() == null || member.phones().isEmpty()) { return null; }
            var phone = member.phones().getFirst();
            return (phone.prefix() == null ? "" : phone.prefix()) + phone.number();
        }).orElse(null);
    }
    @Override public void update(String memberId, String accountId, String phone, Boolean imageConsent, String version, Instant at) {
        members.forAccount(memberId, accountId).ifPresent(member -> members.updateOnboarding(member, phone, imageConsent, version, at));
    }
}
