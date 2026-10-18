package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AuditQuery {
    private final AuditRepository repository;
    public AuditQuery(AuditRepository repository) { this.repository = repository; }

    public LastChange lastChange(String entityType, String entityId) {
        return repository.lastChange(entityType, entityId).map(this::project).orElse(null);
    }

    /** E11-T10 (INC-38): {@link #lastChange} of a whole list in one read; an entity never changed is absent. */
    public java.util.Map<String, LastChange> lastChanges(String entityType, java.util.Collection<String> entityIds) {
        var result = new java.util.HashMap<String, LastChange>();
        repository.lastChanges(entityType, entityIds).forEach((id, entry) -> result.put(id, project(entry)));
        return result;
    }

    public LastChange lastPlatformChange(String entityType, String entityId) {
        return repository.lastPlatformChange(entityType, entityId).map(this::project).orElse(null);
    }

    private LastChange project(AuditEntry entry) {
        return new LastChange(entry.at(), entry.actorName(), entry.action());
    }

    public record LastChange(Instant at, String actorName, AuditAction action) { }
}
