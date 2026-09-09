package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.domain.TeamMembershipChanged;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Identity-owned application boundary; catalog callers never access identity persistence. */
@Service
public class TeamMembershipService {
    public record AdminProfile(String shortName, LocalDate since, boolean active) { }
    public record TeamMembership(String id, String accountId, String memberId, Set<String> roles,
                                 String status, String instructorId, AdminProfile adminProfile, long version) { }
    private final MembershipRepository memberships;
    private final AccountRepository accounts;
    private final AccountSessionRepository sessions;
    private final AuditActorProvider actors;
    private final AuditWriter audit;
    private final EventPublisher events;
    private final Clock clock;
    public TeamMembershipService(MembershipRepository memberships, AccountRepository accounts, AccountSessionRepository sessions,
                                 AuditActorProvider actors, AuditWriter audit, EventPublisher events, Clock clock) {
        this.memberships = memberships; this.accounts = accounts; this.sessions = sessions;
        this.actors = actors; this.audit = audit; this.events = events; this.clock = clock;
    }
    private TeamMembership view(Membership item) {
        var admin = item.adminProfile();
        return new TeamMembership(item.id(), item.accountId(), item.memberId(),
                item.roles().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()), item.status().name(),
                item.instructorId(), admin == null ? null : new AdminProfile(admin.shortName(), admin.since(), admin.active()), item.version());
    }
    public Optional<TeamMembership> findForMember(String memberId) { return memberships.findByMemberId(memberId).map(this::view); }
    public String accountName(String accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_ACTIVE)).name();
    }
    public TeamMembership forMember(String memberId) {
        return view(memberships.findByMemberId(memberId).orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_ACTIVE)));
    }
    public TeamMembership get(String id) {
        return view(memberships.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }
    public List<TeamMembership> administrators(boolean includeInactive) {
        return memberships.findAll().stream().filter(item -> item.adminProfile() != null)
                .filter(item -> includeInactive || item.adminProfile().active()).map(this::view)
                .sorted(Comparator.comparing((TeamMembership item) -> item.adminProfile().shortName()).thenComparing(TeamMembership::id)).toList();
    }
    public long activeAdmins() { return memberships.activeAdmins(); }
    public void requireActive(TeamMembership item, String accountId) {
        if (!Objects.equals(item.accountId(), accountId) || !item.status().equals("ACTIVE")
                || accounts.findById(accountId).filter(account -> account.status() == Account.Status.ACTIVE).isEmpty()) {
            throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE);
        }
    }
    public Map<String, Object> roleSnapshot(String id) { var item = get(id); return Map.of("roles", new TreeSet<>(item.roles()), "memberId", item.memberId()); }

    @Transactional
    @Audited(action = AuditAction.MEMBER_ROLES_CHANGED, entityType = "'Membership'", entity = "#id",
            before = "roleSnapshot(#id)", member = "#result['memberId']", reason = "#reason")
    public Map<String, Object> save(String id, Set<String> roles, String instructorId, AdminProfile adminProfile, String reason) {
        var old = memberships.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var nextRoles = roles.stream().map(Role::valueOf).collect(java.util.stream.Collectors.toSet());
        var nextAdmin = adminProfile == null ? null : new Membership.AdminProfile(adminProfile.shortName(), adminProfile.since(), adminProfile.active());
        if (!old.roles().equals(nextRoles) || !Objects.equals(old.instructorId(), instructorId) || !Objects.equals(old.adminProfile(), nextAdmin)) {
            var status = nextRoles.isEmpty() ? Membership.Status.SUSPENDED : old.status();
            Role profile = old.defaultProfile() != null && nextRoles.contains(old.defaultProfile()) ? old.defaultProfile() : null;
            var actor = actors.current();
            var next = new Membership(old.id(), old.accountId(), old.clubId(), old.memberId(), nextRoles, status, profile,
                    profile != null && old.rememberProfile(), instructorId, old.createdAt(), old.lastAccessAt(), nextAdmin,
                    old.version() + 1, clock.instant(), old.createdByAccountId(), actor.accountId());
            memberships.saveTeam(next);
            if (!old.roles().equals(nextRoles)) {
                accounts.touchSessions(old.accountId());
                if (status == Membership.Status.SUSPENDED) { sessions.revokeClub(old.accountId(), old.clubId(), clock.instant()); }
                events.publish(new TeamMembershipChanged(old.clubId(), id, clock.instant(), Map.of("accountId", old.accountId(), "clubId", old.clubId(),
                        "rolesBefore", new TreeSet<>(old.roles()), "rolesAfter", new TreeSet<>(nextRoles)), actor.accountId(),
                        actor.accountId() == null ? DomainEvent.Origin.SYSTEM : DomainEvent.Origin.BACKOFFICE));
            }
            audit.write(new AuditCommand(AuditAction.CATALOG_CHANGED, "Administrator", id, old.memberId(),
                    profileFields(old.adminProfile()), profileFields(nextAdmin), null));
        }
        return Map.of("roles", new TreeSet<>(roles), "memberId", old.memberId());
    }
    private Map<String, Object> profileFields(Membership.AdminProfile profile) {
        return profile == null ? Map.of() : Map.of("shortName", profile.shortName(), "since", profile.since(), "active", profile.active());
    }
}
