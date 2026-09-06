package com.agilityhub.core.platform.application.audit;

/** Identity and background jobs can provide their trusted actor snapshot through this port. */
@FunctionalInterface
public interface AuditActorProvider {
    AuditActor current();
}
