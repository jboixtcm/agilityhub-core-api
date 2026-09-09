package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.persistence.CatalogMigrationProjection;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MigrationCatalogAccess {
    private final CatalogMigrationProjection repository;
    public MigrationCatalogAccess(CatalogMigrationProjection repository) { this.repository=repository; }
    public List<Map<String,Object>> rows(String collection) { return repository.rows(collection); }
    public void lock() { repository.lock(); }
}
