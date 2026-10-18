package com.agilityhub.core.clubs.catalogs.api;

import com.agilityhub.core.clubs.catalogs.application.InstructorUsageCounter;
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
    private LastChange lastChange(String type, String id) { return access.admin() ? CatalogViews.project(audit.lastChange(type, id)) : null; }
    /** E11-T10 (INC-38): the last changes of a whole list in one read (ADMIN only). */
    private java.util.Map<String, LastChange> lastChanges(String type, java.util.List<String> ids) {
        var result = new java.util.HashMap<String, LastChange>();
        if (access.admin()) { audit.lastChanges(type, ids).forEach((id, last) -> result.put(id, CatalogViews.project(last))); }
        return result;
    }
    public CatalogResponses.Instructor instructor(String id) {
        var item = team.instructor(id);
        return instructor(item, access.admin() ? team.usage(item.id()) : null, lastChange("Instructor", item.id()));
    }
    private CatalogResponses.Instructor instructor(Instructor item, InstructorUsageCounter.Usage usage, LastChange lastChange) {
        return new CatalogResponses.Instructor(item.id(), access.admin() ? item.memberId() : null, item.shortName(), item.color(), item.active(),
                usage == null ? null : new CatalogResponses.InstructorUsage(Math.toIntExact(usage.futureClassSessions()), Math.toIntExact(usage.templateClasses())),
                lastChange, item.version());
    }
    /** E11-T10 (INC-38): D17 reads the usage and the last changes once per list. */
    public CatalogItems<CatalogResponses.Instructor> instructors(boolean includeInactive) {
        access.checkInactive(includeInactive);
        var list = team.instructors(includeInactive); var ids = list.stream().map(Instructor::id).toList();
        var usages = access.admin() ? team.usage(ids) : java.util.Map.<String, InstructorUsageCounter.Usage>of(); var changes = lastChanges("Instructor", ids);
        var items = list.stream().map(item -> instructor(item, usages.get(item.id()), changes.get(item.id()))).toList(); return new CatalogItems<>(items, items.size());
    }
    public CatalogResponses.Administrator administrator(String id) { var item = team.administrator(id); return administrator(item, lastChange("Administrator", item.id())); }
    private CatalogResponses.Administrator administrator(TeamMembership item, LastChange lastChange) {
        return new CatalogResponses.Administrator(item.id(), item.memberId(), item.adminProfile().shortName(), item.adminProfile().since(),
                item.adminProfile().active(), lastChange, item.version());
    }
    public CatalogItems<CatalogResponses.Administrator> administrators(boolean includeInactive) {
        var list = team.administrators(includeInactive); var changes = lastChanges("Administrator", list.stream().map(TeamMembership::id).toList());
        var items = list.stream().map(item -> administrator(item, changes.get(item.id()))).toList(); return new CatalogItems<>(items, items.size());
    }
}
