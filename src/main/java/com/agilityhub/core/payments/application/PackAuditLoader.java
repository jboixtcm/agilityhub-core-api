package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.BillingDocuments.PackBalanceRepository;
import com.agilityhub.core.platform.application.audit.AuditableLoader;
import org.springframework.stereotype.Component;

@Component
public class PackAuditLoader implements AuditableLoader {
    private final PackBalanceRepository packs;
    public PackAuditLoader(PackBalanceRepository packs) { this.packs = packs; }
    public String entityType() { return "PackBalance"; }
    public Object load(String id) { return packs.findById(id).orElse(null); }
}
