package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.domain.audit.AuditDiff;
import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditWriter {
    private final AuditRepository repository;
    private final AuditActorProvider actors;
    private final Clock clock;

    public AuditWriter(AuditRepository repository, AuditActorProvider actors, Clock clock) {
        this.repository = repository;
        this.actors = actors;
        this.clock = clock;
    }

    /**
     * S14 R-14-10: an entry without changes or reason is discarded, except for the «event» actions, which record that the
     * action happened (E5-T28: a re-validation of a VALIDATED week that activates its loose DRAFT classes keeps the week's
     * fields, and is still audited).
     */
    static final java.util.Set<AuditAction> EVENT_ACTIONS = java.util.EnumSet.of(AuditAction.WEEK_VALIDATED, AuditAction.DATA_EXPORTED);

    public void write(AuditCommand command) { write(command, null); }

    /**
     * {@link #write(AuditCommand)} with an explicit `origin`, for a mutation without an authenticated user: an anonymous
     * readmission submission is `PUBLIC` (E3-T09, R-04-06). Null keeps the current user's origin.
     */
    public void write(AuditCommand command, String origin) {
        write(command.action(), command.entityType(), command.entityId(), command.memberId(), command.reason(),
                AuditDiff.between(AuditDiff.snapshot(command.before()), AuditDiff.snapshot(command.after())), origin, null);
    }

    /**
     * A change a system process makes on its own, outside any request (E7-T06, ruling E81: the start-up template upgrade):
     * the actor is the process — `actorName` = `process` (`system:…`), role `SYSTEM`, no account — and the origin `SYSTEM`,
     * so the entity's «last change» names it.
     */
    public void writeAsSystem(AuditCommand command, String process) {
        requireText(process, "Audit process is required");
        write(command.action(), command.entityType(), command.entityId(), command.memberId(), command.reason(),
                AuditDiff.between(AuditDiff.snapshot(command.before()), AuditDiff.snapshot(command.after())), "SYSTEM",
                new AuditActor(null, process, "SYSTEM", null, null, null, null, UUID.randomUUID().toString()));
    }

    void write(AuditAction action, String entityType, String entityId, String memberId, String reason,
               List<AuditChange> changes) { write(action, entityType, entityId, memberId, reason, changes, null, null); }

    private void write(AuditAction action, String entityType, String entityId, String memberId, String reason,
               List<AuditChange> changes, String origin, AuditActor system) {
        Objects.requireNonNull(action, "Audit action is required");
        requireText(entityType, "Audit entity type is required");
        requireText(entityId, "Audit entity id is required");
        if (changes.isEmpty() && (reason == null || reason.isBlank()) && !EVENT_ACTIONS.contains(action)) { return; }
        String clubId = TenantContext.current();
        AuditActor actor = system != null ? system : Objects.requireNonNull(actors.current(), "Audit actor is required");
        AuditEntry entry = new AuditEntry(UUID.randomUUID().toString(), clubId, clock.instant(), actor.accountId(),
                actor.name(), actor.role(), actor.impersonatedMemberId(), actor.support(), action, entityType,
                entityId, memberId, changes, reason, actor.ip(), actor.userAgent(), actor.traceId(),
                auditOrigin(origin != null ? origin : com.agilityhub.core.shared.application.CurrentUser.current()==null?null:com.agilityhub.core.shared.application.CurrentUser.current().origin().name()));
        // MongoTemplate participates in the caller's transaction, or inserts immediately without one.
        repository.append(entry);
    }

    /**
     * S14 §3 (amended 26-09, review E5-T22 #1; E5-T24): the audit `origin` is APP · BACKOFFICE · SYSTEM · WEBHOOK · PUBLIC. The
     * request's event origin INSTRUCTOR (CATALEG_ESDEVENIMENTS, which N-04/N-05 route on) is written BACKOFFICE; `actorRole =
     * INSTRUCTOR` tells it apart. Entries stored earlier with INSTRUCTOR still read BACKOFFICE ({@code AuditListProjection}).
     */
    static String auditOrigin(String origin) { return "INSTRUCTOR".equals(origin) ? "BACKOFFICE" : origin; }

    private static void requireText(String text, String message) {
        if (text == null || text.isBlank()) { throw new IllegalArgumentException(message); }
    }
}
