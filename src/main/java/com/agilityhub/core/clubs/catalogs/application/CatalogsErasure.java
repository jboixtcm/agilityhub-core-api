package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.InstructorChanged;
import com.agilityhub.core.clubs.catalogs.persistence.CatalogErasureRepository;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.application.privacy.PrivacyTraces;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * S14 R-14-15, S05 (E11-T01 round 2): an erased member who is an instructor of the club keeps the profile (classes and templates
 * point at it), but its `shortName` becomes «Abonat suprimit #87». `InstructorChanged` goes to the outbox in the erasure's
 * transaction, so the planning caches drop the old name after the commit; its diff carries only the new name, never the old one.
 * The processed `InstructorChanged` records written before (by {@code InstructorWriter}, whose diffs carry the old name) are
 * deleted with the member's other outbox traces (E11-T11); an unprocessed one is left to its consumers, as R-14-15's rule does.
 */
@Component
public class CatalogsErasure implements ErasureStep {
    private final CatalogErasureRepository erasure;
    private final EventPublisher events;
    private final PrivacyTraces traces;
    public CatalogsErasure(CatalogErasureRepository erasure, EventPublisher events, PrivacyTraces traces) {
        this.erasure = erasure; this.events = events; this.traces = traces;
    }

    @Override public ErasureEffect erase(ErasureSubject subject) {
        var effect = ErasureEffect.builder();
        for (var member : subject.members()) {
            String label = subject.memberLabel(member.id());
            erasure.renameInstructor(member.id(), label, subject.at()).ifPresent(instructor -> {
                effect.count("instructors", 1);
                effect.count("domain_events", traces.forgetProcessedEvents("InstructorChanged", instructor.id()));
                events.publish(new InstructorChanged(subject.clubId(), instructor.id(), subject.at(), Map.of("id", instructor.id(), "action", "UPDATED",
                        "diff", Map.of("shortName", Map.of("after", label))), null, DomainEvent.Origin.SYSTEM));
            });
        }
        return effect.build();
    }
}
