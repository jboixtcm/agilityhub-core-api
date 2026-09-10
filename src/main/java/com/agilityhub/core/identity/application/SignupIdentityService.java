package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.*;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SignupIdentityService {
    private final AccountRepository accounts; private final MembershipRepository memberships; private final EventPublisher events; private final Clock clock;
    public SignupIdentityService(AccountRepository accounts,MembershipRepository memberships,EventPublisher events,Clock clock) {
        this.accounts=accounts;this.memberships=memberships;this.events=events;this.clock=clock;
    }
    public String validate(String memberId,String email,String name,String locale,String consentVersion,Instant acceptedAt,boolean readmission) {
        var account=accounts.findByEmail(email).orElse(null);
        if(account==null) {
            account=new Account(UUID.randomUUID().toString(),email,name,locale,null,Set.of(),Account.Status.ACTIVE,new Account.Security(0,null,null,0),Map.of(),false,clock.instant(),null,null,
                    Account.Source.SIGNUP,null,null,List.of(new Account.Consent(Account.ConsentPolicy.CLUB,TenantContext.require(),consentVersion,acceptedAt)),List.of());
            if (!accounts.createIfAbsent(account)) { throw new ApiException(ErrorCode.MEMBERSHIP_EXISTS); }
            events.publish(new IdentityEvent(IdentityEvent.Kind.AccountCreated,TenantContext.require(),account.id(),clock.instant(),Map.of("accountId",account.id(),"source","SIGNUP")));
        } else if(account.status()!=Account.Status.ACTIVE) { throw new ApiException(ErrorCode.INVALID_STATE); }
        var existing=memberships.findByAccountId(account.id()).orElse(null);
        if(existing!=null) {
            if(!readmission || !memberId.equals(existing.memberId()) || existing.status()==Membership.Status.ERASED) { throw new ApiException(ErrorCode.MEMBERSHIP_EXISTS); }
            var roles=new HashSet<>(existing.roles()); roles.add(Role.MEMBER);
            memberships.saveTeam(new Membership(existing.id(),existing.accountId(),existing.clubId(),existing.memberId(),roles,Membership.Status.ACTIVE,
                    Role.MEMBER,false,existing.instructorId(),existing.createdAt(),existing.lastAccessAt(),existing.adminProfile(),existing.version()+1,clock.instant(),existing.createdByAccountId(),null));
        } else {
            existing=new Membership(UUID.randomUUID().toString(),account.id(),TenantContext.require(),memberId,Set.of(Role.MEMBER),Membership.Status.ACTIVE,Role.MEMBER,false,null,clock.instant(),null);
            memberships.insert(existing);
        }
        if(account.consents().stream().noneMatch(c -> c.policy()==Account.ConsentPolicy.CLUB && TenantContext.require().equals(c.clubId()) && consentVersion.equals(c.version()))) {
            accounts.completeOnboarding(account.id(),new Account.Consent(Account.ConsentPolicy.CLUB,TenantContext.require(),consentVersion,acceptedAt));
        }
        events.publish(new IdentityEvent(IdentityEvent.Kind.MembershipChanged,TenantContext.require(),existing.id(),clock.instant(),
                Map.of("accountId",account.id(),"memberId",memberId,"roles",List.of("MEMBER"),"status","ACTIVE")));
        return account.id();
    }
    public List<String> admins() { return memberships.findAll().stream().filter(m -> m.status()==Membership.Status.ACTIVE && m.roles().contains(Role.ADMIN)).map(Membership::accountId).toList(); }
}
