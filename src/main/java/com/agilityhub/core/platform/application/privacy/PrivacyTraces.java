package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.persistence.privacy.PrivacyTraceRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** The technical traces of S14 R-14-15: processed outbox records about the members, and an erased account's replays and security events. */
@Component
public class PrivacyTraces implements ErasureStep, AccountErasureStep {
    private final PrivacyTraceRepository traces;
    public PrivacyTraces(PrivacyTraceRepository traces) { this.traces = traces; }
    @Override public ErasureEffect erase(ErasureSubject subject) {
        return ErasureEffect.builder().count("domain_events", traces.deleteProcessedEvents(subject.memberIds(), subject.dogIds())).build();
    }
    /**
     * For a context step whose aggregate is not the member's but names it (E11-T11: the renamed instructor's earlier
     * `InstructorChanged` carry its old `shortName` in their diff): its processed outbox records of that type go too.
     */
    public long forgetProcessedEvents(String type, String aggregateId) { return traces.deleteProcessedEvents(type, java.util.List.of(aggregateId)); }
    @Override public ErasureEffect eraseAccount(String accountId, String pseudonym, ErasurePlaceholders placeholders, Instant at) {
        return ErasureEffect.builder().count("idempotency_records", traces.deleteReplays(accountId))
                .count("security_events", traces.deleteSecurityEvents(accountId)).build();
    }
}
