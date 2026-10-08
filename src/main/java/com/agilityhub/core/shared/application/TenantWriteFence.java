package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.persistence.TenantWriteCounterRepository;
import org.springframework.stereotype.Service;

/** Application boundary for a maintenance transaction and the tenant's audit/outbox writers to share a fence. */
@Service
public class TenantWriteFence {
    private final TenantWriteCounterRepository counters;
    public TenantWriteFence(TenantWriteCounterRepository counters) { this.counters = counters; }
    public void written(String clubId) { counters.written(clubId); }
    public long lock() { return counters.lock(); }
}
