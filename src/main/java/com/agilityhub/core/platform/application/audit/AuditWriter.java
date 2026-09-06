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

    public void write(AuditCommand command) {
        write(command.action(), command.entityType(), command.entityId(), command.memberId(), command.reason(),
                AuditDiff.between(AuditDiff.snapshot(command.before()), AuditDiff.snapshot(command.after())));
    }

    void write(AuditAction action, String entityType, String entityId, String memberId, String reason,
               List<AuditChange> changes) {
        Objects.requireNonNull(action, "Audit action is required");
        requireText(entityType, "Audit entity type is required");
        requireText(entityId, "Audit entity id is required");
        if (changes.isEmpty() && (reason == null || reason.isBlank())) { return; }
        String clubId = TenantContext.current();
        AuditActor actor = Objects.requireNonNull(actors.current(), "Audit actor is required");
        AuditEntry entry = new AuditEntry(UUID.randomUUID().toString(), clubId, clock.instant(), actor.accountId(),
                actor.name(), actor.role(), actor.impersonatedMemberId(), actor.support(), action, entityType,
                entityId, memberId, changes, reason, actor.ip(), actor.userAgent(), actor.traceId());
        // MongoTemplate participates in the caller's transaction, or inserts immediately without one.
        repository.append(entry);
    }

    private static void requireText(String text, String message) {
        if (text == null || text.isBlank()) { throw new IllegalArgumentException(message); }
    }
}
