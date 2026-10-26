package com.agilityhub.core.migration.application;

import java.nio.file.Path;
import java.time.Instant;

/** Private production reports; local/demo storage must never silently accept named production reports. */
public interface MigrationReportStore {
    void requireReady();
    void put(String key, Path file, Instant expiresAt);
    String downloadUrl(String key, Instant expiresAt);
    void delete(String key);
}
