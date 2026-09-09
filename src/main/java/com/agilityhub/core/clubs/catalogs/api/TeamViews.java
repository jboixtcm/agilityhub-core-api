package com.agilityhub.core.clubs.catalogs.api;

import com.agilityhub.core.clubs.catalogs.application.RoleAssignmentService;
import com.agilityhub.core.clubs.catalogs.persistence.Instructor;
import com.agilityhub.core.identity.application.TeamMembershipService.TeamMembership;
import com.agilityhub.core.platform.application.audit.AuditQuery;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.shared.application.contract.ApiContracts.*;

@Component
public class TeamViews {
    private final RoleAssignmentService team;
    private final CatalogViews access;
    private final AuditQuery audit;
    public TeamViews(RoleAssignmentService team, CatalogViews access, AuditQuery audit) { this.team = team; this.access = access; this.audit = audit; }
    private LastChange lastChange(String type, String id) {
        if (!access.admin()) { return null; }
        var last = audit.lastChange(type, id);
        return last == null ? null : new LastChange(last.at(), last.actorName(), last.action().name());
    }
    public CatalogResponses.Instructor instructor(String id) { return instructor(team.instructor(id)); }
    private CatalogResponses.Instructor instructor(Instructor item) {
        var usage = access.admin() ? team.usage(item.id()) : null;
        return new CatalogResponses.Instructor(item.id(), access.admin() ? item.memberId() : null, item.shortName(), item.color(), item.active(),
                usage == null ? null : new CatalogResponses.InstructorUsage(Math.toIntExact(usage.futureClassSessions()), Math.toIntExact(usage.templateClasses())),
                lastChange("Instructor", item.id()), item.version());
    }
    public CatalogItems<CatalogResponses.Instructor> instructors(boolean includeInactive) {
        access.checkInactive(includeInactive);
        var items = team.instructors(includeInactive).stream().map(this::instructor).toList(); return new CatalogItems<>(items, items.size());
    }
    public CatalogResponses.Administrator administrator(String id) { return administrator(team.administrator(id)); }
    private CatalogResponses.Administrator administrator(TeamMembership item) {
        return new CatalogResponses.Administrator(item.id(), item.memberId(), item.adminProfile().shortName(), item.adminProfile().since(),
                item.adminProfile().active(), lastChange("Administrator", item.id()), item.version());
    }
    public CatalogItems<CatalogResponses.Administrator> administrators(boolean includeInactive) {
        var items = team.administrators(includeInactive).stream().map(this::administrator).toList(); return new CatalogItems<>(items, items.size());
    }
}
