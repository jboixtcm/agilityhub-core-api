package com.agilityhub.core.platform.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Tenant-scoped catalog boundary for declarative club definitions. Omitted entries are preserved. */
public interface ClubCatalogProvisioner {
    record Change(String section, String key, String id, Map<String, Object> fields) { }
    List<Change> plan(JsonNode catalogs);
    void provision(List<Change> changes);
    Map<String, Object> export();
}
