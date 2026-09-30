package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.domain.AttachmentEntityType;
import com.agilityhub.core.clubs.followup.persistence.AttachmentRepository;
import com.agilityhub.core.clubs.followup.persistence.FollowupItemRepository;
import com.agilityhub.core.clubs.followup.persistence.TaskRepository;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.ModuleGuard;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.DogOwnerAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListDefinition;
import com.agilityhub.core.shared.application.lists.ListDefinition.Field;
import com.agilityhub.core.shared.application.lists.ListDefinition.Type;
import com.agilityhub.core.shared.application.lists.ListQuery;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/**
 * Read-only tenant, ownership, role-per-entity and list guards of the reserved S10 follow-up operations (tasks,
 * observations, attachments, D14; E6-T01); no side effects. E6-T03 serves the operations behind them.
 * A member only reaches their own dogs (S10 §13-13: not the family group's); what they may not see is 404, never 403.
 */
@Service
public class FollowupContractAccess {
    /** D14 pages hold at most 50 rows (S10 §3): the names of a page are resolved at read time. */
    public static final int FOLLOWUP_MAX_SIZE = 50;
    /** `GET /followup` (S10 §6, R-10-13): the universal list allowlist of CONVENCIONS_API §4, pages of 20 or 50 rows. */
    public static final ListDefinition FOLLOWUP = new ListDefinition("followup",
            Map.of("kind", new Field("kind", Type.TEXT), "memberId", new Field("memberId", Type.TEXT), "dogId", new Field("dogId", Type.TEXT),
                    "authorAccountId", new Field("authorAccountId", Type.TEXT), "unread", new Field("unread", Type.BOOLEAN)),
            Map.of("activityAt", "activityAt"), List.of(),
            List.of("memberName", "dogName", "levelCode", "activityAt", "authorName", "textExcerpt", "createdAt", "completedAt"),
            List.of("memberName", "dogName", "levelCode", "activityAt", "authorName", "textExcerpt", "createdAt", "completedAt"),
            List.of("activityAt,desc"),
            Set.of("id", "kind", "taskId", "dogId", "dogName", "levelCode", "memberId", "memberName", "authorName", "authorRole", "authorGender", "textExcerpt",
                    "createdAt", "completedAt", "activityAt", "unread"), FOLLOWUP_MAX_SIZE);
    /** The caller: `memberId` of the member (or of the impersonated member); `staff` = INSTRUCTOR/ADMIN without impersonation. */
    public record Caller(String memberId, boolean staff, boolean impersonated) { }

    private final TaskRepository tasks; private final AttachmentRepository attachments; private final FollowupItemRepository items;
    private final DogOwnerAccess census; private final ModuleGuard modules;
    public FollowupContractAccess(TaskRepository tasks, AttachmentRepository attachments, FollowupItemRepository items, DogOwnerAccess census,
            ModuleGuard modules) {
        this.tasks = tasks; this.attachments = attachments; this.items = items; this.census = census; this.modules = modules;
    }
    public void tenant() { TenantContext.require(); }
    public Caller caller(String memberClaim) {
        var user = CurrentUser.current();
        boolean impersonated = user != null && user.impersonation() != null;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean staff = !impersonated && authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> granted.getAuthority().equals("ROLE_ADMIN") || granted.getAuthority().equals("ROLE_INSTRUCTOR"));
        return new Caller(impersonated ? user.impersonation().memberId() : memberClaim, staff, impersonated);
    }
    /**
     * S01 R-01-07 (ruling E41; E71 for S10, E6-T03 round 5): the roles are a union, and an account's own actions go through the
     * member branch. A staff caller whose token also has MEMBER, and whose member owns the dog, acts on it as that member (the
     * note's attachments, the completion). Another member's dog, or a token without MEMBER, keeps the staff branch.
     */
    public Caller owner(Caller caller, String dogId) {
        if (!caller.staff() || caller.memberId() == null || !memberRole()) { return caller; }
        return census.ownerOf(dogId).filter(caller.memberId()::equals).isPresent() ? new Caller(caller.memberId(), false, false) : caller;
    }
    private static boolean memberRole() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(granted -> granted.getAuthority().equals("ROLE_MEMBER"));
    }
    /** TASK and DOG_OBSERVATIONS are written by INSTRUCTOR/ADMIN only (R-10-11): the impersonation token is IMPERSONATION_DENIED. */
    public void staffWriter(Caller caller) {
        if (caller.impersonated()) { throw new ApiException(ErrorCode.IMPERSONATION_DENIED); }
        if (!caller.staff()) { throw new ApiException(ErrorCode.FORBIDDEN); }
    }
    public void tasksModule() { modules.require(TenantContext.require(), Module.TASKS); }

    /** Staff reach every dog of the club; a member only their own (404 DOG_NOT_ACCESSIBLE, S10 §6 `GET /tasks`). */
    public void dog(Caller caller, String dogId) {
        var owner = census.ownerOf(dogId).orElse(null);
        if (caller.staff()) { if (owner == null) { throw new ApiException(ErrorCode.NOT_FOUND); } return; }
        if (owner == null || !owner.equals(caller.memberId())) { throw new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE); }
    }
    /**
     * A task of another club or of another member's dog is 404. The owner is the dog's current one as the census holds it
     * (R-10-10, amended 27-09, E64), never `Task.memberId`, which the `DogTransferred` consumer refreshes later: right after a
     * transfer the previous owner no longer reaches the task, its attachments or its completion. A deleted task is 404 for
     * every caller, staff included (R-10-10, E6-T01 round-2 review): only ADMIN's `includeDeleted` list shows it.
     */
    public com.agilityhub.core.clubs.followup.persistence.Task task(Caller caller, String id) {
        var task = tasks.findById(id).filter(found -> found.deletedAt() == null).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!caller.staff() && (caller.memberId() == null || !census.ownerOf(task.dogId()).filter(caller.memberId()::equals).isPresent())) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return task;
    }
    /** A visible D14 row of this club (a deleted task's row is hidden: 404). */
    public void followupItem(String id) { items.findById(id).filter(item -> !item.hidden()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    /** An undeclared filter or sort is 400 INVALID_FILTER before anything else (T-10-21). */
    public void followup(MultiValueMap<String, String> params) { ListQuery.parse(FOLLOWUP, params); }

    /** `GET /attachments`: TASK and INSTRUCTOR_NOTE are readable by the dog's owner (also impersonated); DOG_OBSERVATIONS never (404). */
    public void readableEntity(Caller caller, AttachmentEntityType type, String entityId) {
        if (!caller.staff() && !type.memberReadable()) { throw new ApiException(ErrorCode.NOT_FOUND); }
        entity(caller, type, entityId);
    }
    /** `POST /attachments` of TASK/DOG_OBSERVATIONS: INSTRUCTOR/ADMIN, TASKS on, the owning entity in this club. */
    public void writableEntity(Caller caller, AttachmentEntityType type, String entityId) {
        staffWriter(caller);
        tasksModule();
        entity(caller, type, entityId);
    }
    /**
     * `DELETE /attachments/{id}`: live attachment of this club; TASK/DOG_OBSERVATIONS by staff, INSTRUCTOR_NOTE by the owner
     * (also impersonated, or with a staff role next to MEMBER, {@link #owner}).
     */
    public com.agilityhub.core.clubs.followup.persistence.Attachment removableAttachment(Caller caller, String id) {
        var attachment = attachments.findById(id).filter(a -> a.removedAt() == null).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var type = type(attachment.entityType());
        var acting = type.staffOnly() ? caller : owner(caller, attachment.entityId());
        if (type.staffOnly()) { staffWriter(acting); }
        else if (acting.staff()) { throw new ApiException(ErrorCode.FORBIDDEN); }
        entity(acting, type, attachment.entityId());
        return attachment;
    }
    private void entity(Caller caller, AttachmentEntityType type, String entityId) {
        if (type == AttachmentEntityType.TASK) { task(caller, entityId); return; }
        var owner = census.ownerOf(entityId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!caller.staff() && !owner.equals(caller.memberId())) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    private static AttachmentEntityType type(String value) {
        try { return AttachmentEntityType.valueOf(value); } catch (IllegalArgumentException | NullPointerException other) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
}
