package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.persistence.privacy.PrivacyTraceRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The technical traces of S14 R-14-15: processed outbox records about the members (`PUBLISHED` or `FAILED`), the dog names of the
 * run traces, and an erased account's replays and security events. E11-T16: what reaches them after the erasure — a record still
 * `PENDING` when it ran and published since, a run that stored its trace afterwards — goes at S15 R-15-19's monthly
 * `retention-sweep` ({@link RetentionTask}, one item per erased member of the club that still has one).
 */
@Component
public class PrivacyTraces implements ErasureStep, AccountErasureStep, RetentionTask {
    private final PrivacyTraceRepository traces;
    private final ErasureDirectory directory;
    public PrivacyTraces(PrivacyTraceRepository traces, ErasureDirectory directory) { this.traces = traces; this.directory = directory; }
    @Override public ErasureEffect erase(ErasureSubject subject) {
        return ErasureEffect.builder().count("domain_events", traces.deleteProcessedEvents(subject.memberIds(), subject.dogIds()))
                .count("job_runs", traces.forgetDogNames(subject.memberIds())).build();
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

    @Override public String entityType() { return "Member"; }
    @Override public String action() { return "PURGE_ERASED_TRACES"; }
    @Override public String counter() { return "erasedTracesPurged"; }
    /** The erased members of the club with a processed record about them or their dogs, or a run trace naming one of their dogs. */
    @Override public List<String> due(JobContext context) {
        var erased = directory.erased();
        if (erased.isEmpty()) { return List.of(); }
        var owners = new HashMap<String, String>();
        erased.forEach(member -> member.dogIds().forEach(dog -> owners.put(dog, member.id())));
        return List.copyOf(traces.traced(erased.stream().map(ErasureMember::id).toList(), owners));
    }
    /** Only an erased member's traces: a member that is not (or no longer in the club) is out of scope. */
    @Override public boolean apply(JobContext context, String memberId) {
        var member = directory.member(memberId).filter(ErasureMember::erased).orElse(null);
        if (member == null) { return false; }
        return traces.deleteProcessedEvents(Set.of(member.id()), member.dogIds()) + traces.forgetDogNames(Set.of(member.id())) > 0;
    }
}
