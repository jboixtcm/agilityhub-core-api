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
    private final MembershipRepository memberships;private final PlatformClubChanges clubs;private final TeamWriteLock team;
    public ConsoleAdministrators(AccountService accounts,AccountRepository accountRows,MembershipService membershipService,MembershipRepository memberships,
            PlatformClubChanges clubs,TeamWriteLock team) {
        this.accounts=accounts;this.accountRows=accountRows;this.membershipService=membershipService;this.memberships=memberships;this.clubs=clubs;this.team=team;
    }
    public List<Admin> list() { return memberships.findAll().stream().filter(m->m.roles().contains(Role.ADMIN))
            .map(this::view).sorted(Comparator.comparing(Admin::email)).toList(); }
    private Admin view(Membership m) { var a=accountRows.findById(m.accountId()).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        return new Admin(a.id(),a.name(),a.email(),m.status().name(),m.lastAccessAt()); }
    public Change snapshot() { return new Change(clubs.administration().id(),list().stream().map(a->Map.of("accountId",a.accountId(),"status",a.membershipStatus())).toList(),null); }
    @Transactional
    @Audited(action=AuditAction.CLUB_ADMIN_INVITED,entityType="'Club'",entity="#result.id",before="snapshot()",reason="'Administrator invitation'")
    public Change invite(String email,String name) {
        team.lock();var club=clubs.administration();
        var account=accounts.getOrCreate(email,name,club.defaultLocale(),Account.Source.CONSOLE);accounts.active(account.id());
        if(memberships.findByAccountId(account.id()).filter(m->m.roles().contains(Role.ADMIN)&&m.status()==Membership.Status.ACTIVE).isPresent())
            throw new ApiException(ErrorCode.MEMBERSHIP_EXISTS);
        var before=memberships.findByAccountId(account.id()).orElse(null);
        // MODEL_DADES_PLATAFORMA Annex B: only onboarding permits a new administrator without a member record.
        if(!club.onboarding()&&(before==null||before.memberId()==null))
            throw new ApiException(ErrorCode.INVALID_STATE,Map.of("field","memberId"));
        var roles=EnumSet.noneOf(Role.class);if(before!=null) roles.addAll(before.roles());roles.add(Role.ADMIN);
        var member=membershipService.setRoles(account.id(),roles);
        if(member.status()==Membership.Status.SUSPENDED) member=membershipService.resume(account.id());
        // The team lock taken first serializes this with every other administrator writer; the club version orders console edits.
        clubs.administratorChange(club,Map.of("adminInvitation",account.id()));
        return new Change(club.id(),snapshot().admins(),view(member));
    }
    @Transactional
    @Audited(action=AuditAction.CLUB_UPDATED,entityType="'Club'",entity="#result.id",before="snapshot()",reason="'Administrator removed'")
    public Change remove(String accountId) {
        // R-17-09: the same tenant lock as the club's own team writers (RoleAssignmentService), so the count below is read under it.
        team.lock();var club=clubs.administration();var membership=memberships.findByAccountId(accountId).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        if(!membership.roles().contains(Role.ADMIN)) throw new ApiException(ErrorCode.NOT_FOUND);
        if(membership.status()==Membership.Status.ACTIVE) {
            if(memberships.activeAdmins()<=1) throw new ApiException(ErrorCode.LAST_ADMIN);
            clubs.administratorChange(club,Map.of("adminRemoved",accountId));membershipService.suspend(accountId);
        }
        return snapshot();
    }
}
