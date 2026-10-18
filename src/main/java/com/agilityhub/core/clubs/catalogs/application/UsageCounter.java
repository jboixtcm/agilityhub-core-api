package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.CatalogKind;
import java.util.Map;

/** Tenant-scoped reference queries; scheduling/training adapters can supply their final storage contracts. */
public interface UsageCounter {
    Map<String, Long> usage(CatalogKind kind, String id);
    /** E11-T10 (INC-38): {@link #usage(CatalogKind, String)} of every id, keyed by id, in a fixed number of reads. */
    Map<String, Map<String, Long>> usage(CatalogKind kind, java.util.Collection<String> ids);
    boolean hasReferences(CatalogKind kind, String id);
}
