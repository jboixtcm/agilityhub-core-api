package com.agilityhub.core.shared.application;

import java.time.Instant;
import java.util.List;

/** P9 retention port: current tenant only, no names or report content cross the boundary. */
public interface MigrationReportRetention {
    List<String> expired(Instant at);
    boolean purge(String id, Instant at);
}
