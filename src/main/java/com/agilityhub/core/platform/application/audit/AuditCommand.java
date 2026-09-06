package com.agilityhub.core.platform.application.audit;

/** Manual callers pass annotated objects; AuditWriter applies the same masking as the aspect. */
public record AuditCommand(AuditAction action, String targetType, String targetId, String memberId,
                           Object before, Object after, String reason) { }
