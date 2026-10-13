package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.PlatformClubChanges;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** S17's identity boundary. Administrator contact details are configuration, never census data. */
@Service
public class ConsoleAdministrators implements com.agilityhub.core.platform.application.ConsoleAdministratorAccess {
    private final AccountService accounts;private final AccountRepository accountRows;private final MembershipService membershipService;
    private final MembershipRepository memberships;private final PlatformClubChanges clubs;
    public ConsoleAdministrators(AccountService accounts,AccountRepository accountRows,MembershipService membershipService,MembershipRepository memberships,PlatformClubChanges clubs) {
        this.accounts=accounts;this.accountRows=accountRows;this.membershipService=membershipService;this.memberships=memberships;this.clubs=clubs;
    }
    public List<Admin> list() { return memberships.findAll().stream().filter(m->m.roles().contains(Role.ADMIN))
            .map(this::view).sorted(Comparator.comparing(Admin::email)).toList(); }
    private Admin view(Membership m) { var a=accountRows.findById(m.accountId()).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        return new Admin(a.id(),a.name(),a.email(),m.status().name(),m.lastAccessAt()); }
    public Change snapshot() { return new Change(clubs.club().id(),list().stream().map(a->Map.of("accountId",a.accountId(),"status",a.membershipStatus())).toList(),null); }
    @Transactional
    @Audited(action=AuditAction.CLUB_UPDATED,entityType="'Club'",entity="#result.id",before="snapshot()",reason="'Administrator invitation'")
    public Change invite(String email,String name) {
        var club=clubs.club();
        var account=accounts.getOrCreate(email,name,club.defaultLocale(),Account.Source.CONSOLE);accounts.active(account.id());
        if(memberships.findByAccountId(account.id()).filter(m->m.roles().contains(Role.ADMIN)&&m.status()==Membership.Status.ACTIVE).isPresent())
            throw new ApiException(ErrorCode.MEMBERSHIP_EXISTS);
        var before=memberships.findByAccountId(account.id()).orElse(null);
        var roles=EnumSet.noneOf(Role.class);if(before!=null) roles.addAll(before.roles());roles.add(Role.ADMIN);
        var member=membershipService.setRoles(account.id(),roles);
        if(member.status()==Membership.Status.SUSPENDED) member=membershipService.resume(account.id());
        // The club version serializes simultaneous invitations/removals and protects the last administrator.
        clubs.write(club,Map.of(),"ClubUpdated",Map.of("adminInvitation",account.id()));
        return new Change(club.id(),snapshot().admins(),view(member));
    }
    @Transactional
    @Audited(action=AuditAction.CLUB_UPDATED,entityType="'Club'",entity="#result.id",before="snapshot()",reason="'Administrator removed'")
    public Change remove(String accountId) {
        var club=clubs.club();var membership=memberships.findByAccountId(accountId).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        if(!membership.roles().contains(Role.ADMIN)) throw new ApiException(ErrorCode.NOT_FOUND);
        if(membership.status()==Membership.Status.ACTIVE) {
            if(memberships.activeAdmins()<=1) throw new ApiException(ErrorCode.LAST_ADMIN);
            clubs.write(club,Map.of(),"ClubUpdated",Map.of("adminRemoved",accountId));membershipService.suspend(accountId);
        }
        return snapshot();
    }
}
