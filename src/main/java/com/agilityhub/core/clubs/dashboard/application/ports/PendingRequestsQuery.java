package com.agilityhub.core.clubs.dashboard.application.ports;


public interface PendingRequestsQuery {
    /** REQUESTED only. Leave requests remain visible when INACTIVITY is disabled. */
    Counts counts(String clubId);
    record Counts(int inactivity, int leaves) { }
}
