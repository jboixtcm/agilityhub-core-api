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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuditWriter {
    private final AuditRepository repository;
    private final AuditActorProvider actors;
    private final Clock clock;
    private final TransactionTemplate independentTransaction;

    public AuditWriter(AuditRepository repository, AuditActorProvider actors, Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.actors = actors;
        this.clock = clock;
        independentTransaction = new TransactionTemplate(transactionManager);
        independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void write(AuditCommand command) {
        write(command.action(), command.targetType(), command.targetId(), command.memberId(), command.reason(),
                AuditDiff.between(AuditDiff.snapshot(command.before()), AuditDiff.snapshot(command.after())));
    }

    void write(AuditAction action, String targetType, String targetId, String memberId, String reason,
               List<AuditChange> changes) {
        Objects.requireNonNull(action, "Audit action is required");
        requireText(targetType, "Audit target type is required");
        requireText(targetId, "Audit target id is required");
        if (changes.isEmpty() && (reason == null || reason.isBlank())) { return; }
        String clubId = TenantContext.current();
        AuditActor actor = Objects.requireNonNull(actors.current(), "Audit actor is required");
        List<AuditChange> capturedChanges = List.copyOf(changes);
        Runnable append = () -> {
            AuditEntry entry = new AuditEntry(UUID.randomUUID().toString(), clubId, clock.instant(), actor.accountId(),
                    actor.name(), actor.role(), actor.impersonatedMemberId(), actor.support(), action, targetType,
                    targetId, memberId, capturedChanges, reason, actor.ip(), actor.userAgent(), actor.traceId());
            if (clubId == null) {
                repository.append(entry);
            } else {
                try (var scope = TenantContext.open(clubId)) { repository.append(entry); }
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The original Mongo session remains bound during afterCommit. Suspend it before writing.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    independentTransaction.executeWithoutResult(status -> append.run());
                }
            });
        } else {
            append.run();
        }
    }

    private static void requireText(String text, String message) {
        if (text == null || text.isBlank()) { throw new IllegalArgumentException(message); }
    }
}
