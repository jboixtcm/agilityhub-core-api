package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.clubs.census.domain.CensusRules;
import com.agilityhub.core.platform.application.CountryContacts;
import com.agilityhub.core.shared.application.MemberOnboardingAccess;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

@Service
public class MemberOnboardingService implements MemberOnboardingAccess {
    private final CensusRepository<Member> members; private final CountryContacts countries;
    public MemberOnboardingService(CensusRepository<Member> members, CountryContacts countries) { this.members = members; this.countries = countries; }
    private Optional<Member> member(String id, String account) { return members.findById(id).filter(item -> account.equals(item.accountId) && !"ERASED".equals(item.status)); }
    @Override public String phone(String memberId, String accountId) {
        return member(memberId, accountId).map(member -> {
            if (rows(member.phones).isEmpty()) { return null; }
            var phone = member.phones.getFirst(); return string(phone.getOrDefault("prefix", "")) + phone.get("number");
        }).orElse(null);
    }
    @Override public void update(String memberId, String accountId, String phone, Boolean imageConsent, String version, Instant at) {
        member(memberId, accountId).ifPresent(member -> {
            CensusRules.mutable(member.erasedAt); if (phone == null && imageConsent == null) { return; }
            if (phone != null) {
                var phones = new ArrayList<>(rows(member.phones));
                var primary = countries.phone(null, phone, phones.isEmpty() ? null : string(phones.getFirst().get("label")));
                if (phones.isEmpty()) { phones.add(primary); } else { phones.set(0, primary); } member.phones = phones;
            }
            if (imageConsent != null) {
                member.consents = ConsentLedgers.image(member.consents,imageConsent,version,at,accountId);
            }
            members.save(member);
        });
    }
}
