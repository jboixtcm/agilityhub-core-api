package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.domain.audit.AuditDiff;
import java.util.List;

/** R-14-09: the audit's masked diff, for an event payload that must carry no more than the audit (E3-T09: `SignupEdited`). */
public final class AuditMasking {
    private AuditMasking() { }

    /** The changes between two snapshots, with the same paths and masking as an {@code AuditEntry}. */
    public static List<AuditChange> changes(Object before, Object after) {
        return AuditDiff.between(AuditDiff.snapshot(before), AuditDiff.snapshot(after));
    }
}
