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
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.audit.AuditWriter audits;
    @org.springframework.beans.factory.annotation.Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    public MemberOnboardingService(CensusRepository<Member> members, CountryContacts countries) { this.members = members; this.countries = countries; }
    private Optional<Member> member(String id, String account) { return members.findById(id).filter(item -> account.equals(item.accountId) && !"ERASED".equals(item.status)); }
    /**
     * The initial state of onboarding's image box. PRIV-05 (owner's decision of 13-11-2026): `true` only when the image permission
     * in force was granted by the member's own account (onboarding, or a sign-up or readmission sent from the member's app:
     * `byAccountId` / `actorAccountId`); a grant recorded by the migration, by a data operation or by staff (an ADMIN's edit, an
     * impersonation), or by a public sign-up sent before the member had an account, does not pre-tick it and reads as no answer
     * (`null`). A refusal reads `false`. The stored values are kept as they are.
     */
    @Override public Boolean imageConsent(String memberId, String accountId) {
        return member(memberId, accountId).map(member -> {
            var original = map(map(member.consents).get("imageRights"));
            if (original.isEmpty()) return null;
            var image = com.agilityhub.core.shared.domain.ConsentGrant.image(original);
            if (!Boolean.TRUE.equals(image.get("granted"))) { return Boolean.FALSE; }
            return grantedByOwnAccount(image, member.accountId) ? Boolean.TRUE : null;
        }).orElse(null);
    }
    /** PRIV-05: the account that wrote the entry (an edit's `byAccountId`, a sign-up row's `actorAccountId`) is the member's own. */
    static boolean grantedByOwnAccount(Map<String, Object> image, String accountId) {
        return accountId != null && (accountId.equals(image.get("byAccountId")) || accountId.equals(image.get("actorAccountId")));
    }
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
            Object consents = imageConsent == null ? null : CensusAudit.view(member, mapper).get("consents");
            if (imageConsent != null) {
                member.consents = ConsentLedgers.image(member.consents,imageConsent,version,at,accountId);
            }
            members.save(member);
            // R-14-09 (INC-38, E11-T10): the image consent given at onboarding (S01 §14) is audited like the ADMIN's edit.
            if (imageConsent != null) { CensusAudit.consentChange(audits, member.id, consents, CensusAudit.view(member, mapper).get("consents")); }
        });
    }
}
