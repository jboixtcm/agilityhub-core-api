package com.agilityhub.core.platform.application.audit;

/** Manual callers pass annotated objects; AuditWriter applies the same masking as the aspect. */
public record AuditCommand(AuditAction action, String entityType, String entityId, String memberId,
                           Object before, Object after, String reason) { }
