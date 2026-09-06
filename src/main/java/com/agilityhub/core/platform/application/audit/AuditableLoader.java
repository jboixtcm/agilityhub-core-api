package com.agilityhub.core.platform.application.audit;

/** Loaders must use the current tenant; missing objects return null (create/delete). */
public interface AuditableLoader {
    String targetType();
    Object load(String targetId);
}
