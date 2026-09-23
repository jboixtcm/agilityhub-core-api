package com.agilityhub.core.platform.application.jobs;

import java.util.Map;

/** One planned unit of work; `action` is prefixed with `WOULD_` in a dry run (R-15-08). */
public record JobItem(String entityType, String entityId, String action, Map<String, Object> detail) {
    public JobItem { detail = detail == null ? Map.of() : Map.copyOf(detail); }
    public JobItem(String entityType, String entityId, String action) { this(entityType, entityId, action, Map.of()); }
}
