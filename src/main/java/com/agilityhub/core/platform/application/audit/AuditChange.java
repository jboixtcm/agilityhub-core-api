package com.agilityhub.core.platform.application.audit;

/** JSON-compatible, already masked values. Constructed by AuditDiff. */
public record AuditChange(String path, Object before, Object after) { }
