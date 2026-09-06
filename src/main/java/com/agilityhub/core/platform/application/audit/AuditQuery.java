package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.persistence.audit.AuditEntry;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AuditQuery {
    private final AuditRepository repository;
    public AuditQuery(AuditRepository repository) { this.repository = repository; }

    public LastChange lastChange(String targetType, String targetId) {
        return repository.lastChange(targetType, targetId).map(this::project).orElse(null);
    }

    public LastChange lastPlatformChange(String targetType, String targetId) {
        return repository.lastPlatformChange(targetType, targetId).map(this::project).orElse(null);
    }

    private LastChange project(AuditEntry entry) {
        return new LastChange(entry.at(), entry.actorName(), entry.action());
    }

    public record LastChange(Instant at, String actorName, AuditAction action) { }
}
