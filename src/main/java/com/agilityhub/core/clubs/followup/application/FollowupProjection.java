package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.dashboard.application.DashboardQuery;
import com.agilityhub.core.clubs.followup.domain.CensusForeignEvent;
import com.agilityhub.core.clubs.followup.domain.FollowupRules;
import com.agilityhub.core.clubs.followup.persistence.FollowupItemRepository;
import com.agilityhub.core.clubs.followup.persistence.FollowupReadMarkRepository;
import com.agilityhub.core.clubs.followup.persistence.TaskRepository;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The S03 consumers of S10 follow-up (§7), idempotent by construction: each row has a derived id, so a replayed event
 * writes nothing new. `MemberNoteChanged` upserts the dog's one MEMBER_NOTE row (R-10-12/13): `activityAt` = the change's
 * `occurredAt`, the excerpt of the note's current text, the author = the member who wrote it (the event's `memberId`, the
 * owner at that moment: their account, first name and gender, never the dog's current owner, E64), and the row leaves
 * every read mark, so it is unread again for everyone but its author; an older replay only refreshes the excerpt.
 * `DogTransferred` moves the dog's tasks and rows to the dog's current owner as the census holds it, never to the event's
 * destination, so the transfers delivered late or out of order end with the last one (the task follows the dog, S03
 * R-03-14, E64). `DogDeactivated` changes nothing.
 */
@Service
public class FollowupProjection {
    private final FollowupItemRepository items; private final FollowupReadMarkRepository marks; private final TaskRepository tasks;
    private final FollowupCensusAccess census; private final DashboardQuery dashboard; private final Clock clock;
    public FollowupProjection(FollowupItemRepository items, FollowupReadMarkRepository marks, TaskRepository tasks, FollowupCensusAccess census,
            DashboardQuery dashboard, Clock clock) {
        this.items = items; this.marks = marks; this.tasks = tasks; this.census = census; this.dashboard = dashboard; this.clock = clock;
    }

    @Transactional
    public void memberNote(CensusForeignEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            String dogId = event.text("dogId");
            var note = census.instructorNote(dogId).orElse(null); var dog = census.dog(dogId).orElse(null);
            if (note == null || dog == null) { return; }
            String excerpt = FollowupRules.excerpt(note.text()), rowId = FollowupItemRepository.noteRowId(event.clubId(), dogId);
            var stored = items.findById(rowId).orElse(null);
            if (stored != null && stored.activityAt() != null && !event.occurredAt().isAfter(stored.activityAt())) { items.noteText(dogId, excerpt, clock.instant()); return; }
            String writer = event.text("memberId") != null ? event.text("memberId") : dog.memberId();
            var member = census.members(List.of(writer)).get(writer);
            String author = member != null && member.accountId() != null ? member.accountId() : note.updatedByAccountId();
            items.note(dogId, dog.memberId(), author, member == null ? "" : member.firstName(), member == null ? null : member.gender(), excerpt, event.occurredAt(),
                    clock.instant());
            marks.unreadForEveryone(rowId, clock.instant());
            dashboard.invalidateCountersAfterCommit(event.clubId());
        }
    }
    @Transactional
    public void transferred(CensusForeignEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            String dogId = event.text("dogId");
            String owner = dogId == null ? null : census.dog(dogId).map(FollowupCensusAccess.Dog::memberId).orElse(null);
            if (owner == null) { return; }
            tasks.transfer(dogId, owner); items.transfer(dogId, owner);
        }
    }

    /** Durable consumer names (the outbox records them per event): the D14 projection of S03's events. */
    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        @Bean("followup.MemberNoteChanged") DomainEventHandler<CensusForeignEvent> memberNote(FollowupProjection projection) {
            return handler("MemberNoteChanged", projection::memberNote);
        }
        @Bean("followup.DogTransferred") DomainEventHandler<CensusForeignEvent> dogTransferred(FollowupProjection projection) {
            return handler("DogTransferred", projection::transferred);
        }
        private static DomainEventHandler<CensusForeignEvent> handler(String type, java.util.function.Consumer<CensusForeignEvent> work) {
            return new DomainEventHandler<>() {
                public String eventType() { return type; } public Class<CensusForeignEvent> eventClass() { return CensusForeignEvent.class; }
                public void handle(String id, CensusForeignEvent event) { work.accept(event); }
            };
        }
    }
}
