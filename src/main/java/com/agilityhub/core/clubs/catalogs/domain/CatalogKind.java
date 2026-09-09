package com.agilityhub.core.clubs.catalogs.domain;

public enum CatalogKind {
    LEVEL("Level", "LevelChanged"), RING("Ring", "RingChanged"), FAQ("FaqEntry", "FaqChanged");
    private final String entityType;
    private final String eventType;
    CatalogKind(String entityType, String eventType) { this.entityType = entityType; this.eventType = eventType; }
    public String entityType() { return entityType; }
    public String eventType() { return eventType; }
}
