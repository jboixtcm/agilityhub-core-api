package com.agilityhub.core.platform.application.audit;

/**
 * E11-T10 (INC-38): the `entityLabel` snapshot of an audited entity (S14 §3: «Laura Serra Vidal», «bookings.maxCurrentWeek»), read
 * in the current tenant when the entry is written; an entity type without a labeler, or a missing entity (null), has none and the
 * list shows `entityType · entityId`. A label never holds more than the screen already shows of the entity.
 */
public interface AuditLabeler {
    String entityType();
    String label(String entityId);
}
