package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.shared.application.TeamMemberAccess;
import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.identity.application.TeamMembershipService;
import com.agilityhub.core.identity.application.TeamMembershipService.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** S05 team changes and S03 role assignment share the same transactional invariant checks. */
@Service
public class RoleAssignmentService {
    public record Created(String id, boolean created) { }
    private final InstructorRepository instructors;
    private final InstructorWriter writer;
    private final InstructorUsageCounter usage;
    private final TeamMembershipService memberships;
    private final TeamMemberAccess members;
    private final IdentityTransactions transactions;
    private final ClubConfigService configs;
    private final ClubClock clubClock;
    private final Clock clock;
    private final AuditActorProvider actors;
    public RoleAssignmentService(InstructorRepository instructors, InstructorWriter writer, InstructorUsageCounter usage,
            TeamMembershipService memberships, TeamMemberAccess members, IdentityTransactions transactions,
            ClubConfigService configs, ClubClock clubClock, Clock clock, AuditActorProvider actors) {
        this.instructors = instructors; this.writer = writer; this.usage = usage; this.memberships = memberships;
        this.members = members; this.transactions = transactions; this.configs = configs; this.clubClock = clubClock; this.clock = clock; this.actors = actors;
    }
    public List<Instructor> instructors(boolean includeInactive) {
        return instructors.findAll().stream().filter(item -> includeInactive || item.active())
                .sorted(Comparator.comparing(Instructor::shortName).thenComparing(Instructor::id)).toList();
    }
    public Instructor instructor(String id) { return instructors.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public InstructorUsageCounter.Usage usage(String id) { return usage.usage(id); }
    public TeamMembership administrator(String id) {
        var item = memberships.get(id);
        if (item.adminProfile() == null) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return item;
    }
    public List<TeamMembership> administrators(boolean includeInactive) { return memberships.administrators(includeInactive); }
    private <T> T write(Supplier<T> work) { return transactions.run(() -> { instructors.lock(); return work.get(); }); }
    private TeamMembership activeMember(String memberId) {
        var member = members.teamMember(memberId);
        if (!"ACTIVE".equals(member.status()) || member.accountId() == null) { throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE); }
        var membership = memberships.forMember(memberId); memberships.requireActive(membership, member.accountId()); return membership;
    }
    private String shortName(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 12) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        return value.strip();
    }
    private String color(String value) {
        if (value == null || !value.matches("#[0-9A-Fa-f]{6}")) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        return value;
    }
    private LocalDate since(LocalDate value) {
        if (value == null || value.isAfter(clubClock.today(TenantContext.require()))) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        return value;
    }
    private String defaultName(TeamMembership member) {
        String name = members.teamMember(member.memberId()).firstName();
        if (name == null || name.isBlank()) { name = memberships.accountName(member.accountId()); }
        name = name.strip().split("\\s+")[0];
        return shortName(name.substring(0, Math.min(12, name.length())));
    }
    private void version(long actual, Long expected) {
        if (expected == null || expected != actual) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
    private Set<String> toggle(Set<String> roles, String role, boolean enabled) {
        var next = new HashSet<>(roles); if (enabled) { next.add(role); } else { next.remove(role); } return next;
    }
    private Instructor build(Instructor old, String memberId, String name, String color, boolean active) {
        var actor = actors.current().accountId(); var now = clock.instant();
        return new Instructor(old == null ? UUID.randomUUID().toString() : old.id(), TenantContext.require(), memberId,
                shortName(name), color(color), active, old == null ? 0 : old.version() + 1,
                old == null ? now : old.createdAt(), now, old == null ? actor : old.createdByAccountId(), actor);
    }
    private void saveInstructor(Instructor old, Instructor next) {
        var item = next == null ? old : next; writer.save(item.id(), item.memberId(), writer.fields(old), next);
    }
    private void checkRemoval(TeamMembership member, Set<String> next, boolean leave) {
        if (member.roles().contains("ADMIN") && !next.contains("ADMIN") && memberships.activeAdmins() <= 1
                && member.status().equals("ACTIVE")) { throw new ApiException(ErrorCode.LAST_ADMIN); }
        var instructor = instructors.forMember(member.memberId()).orElse(null);
        if (!leave && instructor != null && instructor.active() && !next.contains("INSTRUCTOR")
                && usage.usage(instructor.id()).futureClassSessions() > 0) {
            throw new ApiException(ErrorCode.INSTRUCTOR_IN_USE, usage.usage(instructor.id()).details());
        }
    }
    private void apply(TeamMembership member, Set<String> roles, Instructor instructor, AdminProfile admin, boolean leave, String reason) {
        checkRemoval(member, roles, leave);
        var before = instructors.forMember(member.memberId()).orElse(null);
        if (instructor != null) { saveInstructor(before, instructor); }
        memberships.save(member.id(), roles, instructor == null ? null : instructor.id(), admin, reason);
    }
    public Created createInstructor(String memberId, String name, String color) {
        return write(() -> {
            var member = activeMember(memberId); var old = instructors.forMember(memberId).orElse(null);
            var next = build(old, memberId, name, color, true);
            apply(member, toggle(member.roles(), "INSTRUCTOR", true), next, member.adminProfile(), false, null);
            return new Created(next.id(), old == null);
        });
    }
    public void updateInstructor(String id, String name, String color, Boolean active, Long expected) {
        write(() -> {
            var old = instructor(id); version(old.version(), expected); var member = memberships.forMember(old.memberId());
            boolean enabled = active == null ? old.active() : active;
            if (enabled && !old.active()) { activeMember(old.memberId()); }
            var next = build(old, old.memberId(), name == null ? old.shortName() : name, color == null ? old.color() : color, enabled);
            apply(member, toggle(member.roles(), "INSTRUCTOR", enabled), next, member.adminProfile(), false, null); return null;
        });
    }
    public void deleteInstructor(String id) {
        write(() -> {
            var old = instructor(id);
            if (usage.hasReferences(id)) { throw new ApiException(ErrorCode.INSTRUCTOR_IN_USE, usage.usage(id).details()); }
            var member = memberships.forMember(old.memberId()); var roles = toggle(member.roles(), "INSTRUCTOR", false);
            checkRemoval(member, roles, false); saveInstructor(old, null);
            memberships.save(member.id(), roles, null, member.adminProfile(), null); return null;
        });
    }
    public Created createAdministrator(String memberId, String name, LocalDate since) {
        return write(() -> {
            var member = activeMember(memberId);
            apply(member, toggle(member.roles(), "ADMIN", true), instructors.forMember(memberId).orElse(null),
                    new AdminProfile(shortName(name), since(since), true), false, null);
            return new Created(member.id(), member.adminProfile() == null);
        });
    }
    public void updateAdministrator(String id, String name, LocalDate since, Boolean active, Long expected) {
        write(() -> {
            var member = administrator(id); version(member.version(), expected); var old = member.adminProfile();
            boolean enabled = active == null ? old.active() : active;
            if (enabled && !old.active()) { activeMember(member.memberId()); }
            apply(member, toggle(member.roles(), "ADMIN", enabled), instructors.forMember(member.memberId()).orElse(null),
                    new AdminProfile(name == null ? old.shortName() : shortName(name), since == null ? old.since() : since(since), enabled), false, null); return null;
        });
    }
    public void deleteAdministrator(String id) {
        write(() -> {
            var member = administrator(id);
            apply(member, toggle(member.roles(), "ADMIN", false), instructors.forMember(member.memberId()).orElse(null), null, false, null); return null;
        });
    }
    public Set<String> setRoles(String memberId, Set<String> roles) {
        return write(() -> {
            if (roles == null || roles.stream().anyMatch(role -> role == null || !Set.of("MEMBER", "INSTRUCTOR", "ADMIN").contains(role))) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
            if (!roles.contains("MEMBER")) { throw new ApiException(ErrorCode.ROLE_MEMBER_REQUIRED); }
            var member = activeMember(memberId);
            if (member.roles().contains("ADMIN") && !roles.contains("ADMIN") && member.accountId().equals(actors.current().accountId())) {
                throw new ApiException(ErrorCode.CANNOT_CHANGE_OWN_ADMIN_ROLE);
            }
            synchronize(member, roles, false, null); return Set.copyOf(roles);
        });
    }
    private void synchronize(TeamMembership member, Set<String> roles, boolean leave, String reason) {
        var instructor = instructors.forMember(member.memberId()).orElse(null);
        if (roles.contains("INSTRUCTOR") || instructor != null) {
            String name = instructor == null ? defaultName(member) : instructor.shortName();
            var config = configs.get(TenantContext.require());
            String color = instructor == null ? (config.ringPalette().isEmpty() ? config.primaryColor() : config.ringPalette().getFirst()) : instructor.color();
            instructor = build(instructor, member.memberId(), name, color, roles.contains("INSTRUCTOR"));
        }
        var admin = member.adminProfile();
        if (roles.contains("ADMIN") || admin != null) {
            admin = new AdminProfile(admin == null ? defaultName(member) : admin.shortName(),
                    admin == null ? clubClock.today(TenantContext.require()) : admin.since(), roles.contains("ADMIN"));
        }
        apply(member, roles, instructor, admin, leave, reason);
    }
    public void memberLeft(String memberId) {
        write(() -> {
            var member = members.teamMember(memberId);
            // Ignore stale leave events after reactivation, and pending members without a membership.
            if (!"LEFT".equals(member.status()) || member.accountId() == null) { return null; }
            var membership = memberships.findForMember(memberId).orElse(null);
            if (membership == null) { return null; }
            boolean keepAdmin = membership.roles().contains("ADMIN") && membership.status().equals("ACTIVE") && memberships.activeAdmins() <= 1;
            synchronize(membership, keepAdmin ? Set.of("ADMIN") : Set.of(), true, keepAdmin ? "LAST_ADMIN_KEPT" : null); return null;
        });
    }
}
