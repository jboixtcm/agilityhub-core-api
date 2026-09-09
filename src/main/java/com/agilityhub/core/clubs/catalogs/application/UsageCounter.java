package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.CatalogKind;
import java.util.Map;

/** Tenant-scoped reference queries; scheduling/training adapters can supply their final storage contracts. */
public interface UsageCounter {
    Map<String, Long> usage(CatalogKind kind, String id);
    boolean hasReferences(CatalogKind kind, String id);
}
