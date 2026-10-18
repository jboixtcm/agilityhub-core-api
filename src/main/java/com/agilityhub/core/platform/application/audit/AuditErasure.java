package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.application.privacy.AccountErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasurePlaceholders;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.application.privacy.MemberDataPackage;
import com.agilityhub.core.platform.application.privacy.MemberDataSource;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * S14 R-14-15 `AuditEntry`: an entry about an erased member (`memberId` or `impersonatedMemberId`, the member or one of its dogs as
 * the entity, E11-T10) or acted by an erased account
 * keeps action, entity, ids and date; `changes → []` and `reason`, `actorName`, `impersonatedName`, `entityLabel`, `ip`,
 * `userAgent` and `details` go.
 */
@Component
public class AuditErasure implements ErasureStep, AccountErasureStep, MemberDataSource {
    private final AuditRepository audit;
    public AuditErasure(AuditRepository audit) { this.audit = audit; }
    @Override public ErasureEffect erase(ErasureSubject subject) {
        // The subject's dogs are the members' current dogs (CensusErasure reads them by `Dog.memberId`), transferred ones included.
        return ErasureEffect.builder().count("audit_entries", audit.eraseMembers(subject.memberIds(), subject.dogIds(), subject.erasedAccountIds())).build();
    }
    @Override public ErasureEffect eraseAccount(String accountId, String pseudonym, ErasurePlaceholders placeholders, Instant at) {
        return ErasureEffect.builder().count("audit_entries", audit.eraseActor(accountId)).build();
    }

    /** S14 R-14-13 `auditEntries[]`: action, date and the actor's role — never a third party's name. */
    @Override public void contribute(ErasureMember member, MemberDataPackage data) {
        data.section("auditEntries", audit.aboutMember(member.id()).stream().map(entry -> {
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("action", entry.action().name()); row.put("at", entry.at().toString()); row.put("actorRole", entry.actorRole() == null ? "SYSTEM" : entry.actorRole());
            return (Object) row;
        }).toList());
    }
}
