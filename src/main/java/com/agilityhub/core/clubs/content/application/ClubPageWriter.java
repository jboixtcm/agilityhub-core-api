package com.agilityhub.core.clubs.content.application;

import com.agilityhub.core.clubs.content.domain.*;
import com.agilityhub.core.clubs.content.persistence.*;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.domain.audit.AuditField;
import com.mongodb.MongoException;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClubPageWriter {
    private final ClubPageRepository pages;
    private final AuditActorProvider actors;
    private final EventPublisher events;
    private final Clock clock;
    public ClubPageWriter(ClubPageRepository pages, AuditActorProvider actors, EventPublisher events, Clock clock) {
        this.pages = pages; this.actors = actors; this.events = events; this.clock = clock;
    }
    public record Written(String key, @AuditField Map<String, Object> details) { }
    @Transactional
    @Audited(action = AuditAction.CATALOG_CHANGED, entityType = "'ClubPage'", entity = "#result.key")
    public Written write(PageContent content, Integer expectedVersion) {
        var before = pages.findByKey(content.key()).orElse(null);
        if (expectedVersion == null && before != null) { throw new ApiException(ErrorCode.DUPLICATE_NAME, Map.of("field", "key")); }
        if (expectedVersion != null && before == null) { throw new ApiException(ErrorCode.NOT_FOUND); }
        if (before != null && expectedVersion != before.version()) { throw new ApiException(ErrorCode.STALE_VERSION); }
        boolean publication = content.active() && (before == null || !before.active() || !content.body().values().equals(before.body().values()));
        var history = before == null ? new ArrayList<ClubPage.History>() : new ArrayList<>(before.history());
        // Archive on unpublication too: later draft edits must never replace an accepted body.
        if (before != null && before.active() && (publication || !content.active())) {
            history.add(new ClubPage.History(before.version(), before.publishedAt(), before.publishedBy(), before.title(), before.body()));
            if (history.size() > 10) { history.removeFirst(); }
        }
        int version = before == null ? 1 : before.version() + (publication ? 1 : 0);
        var now = clock.instant(); var actor = actors.current().accountId();
        var next = new ClubPage(before == null ? UUID.randomUUID().toString() : before.id(), TenantContext.require(), content.key(),
                content.title(), content.body(), version, publication ? now : before == null ? null : before.publishedAt(),
                publication ? actor : before == null ? null : before.publishedBy(), content.active(), new ClubPage.Change(now, actor),
                history, before == null ? 1 : before.revision() + 1);
        try {
            if (before == null) { pages.insert(next); } else { pages.update(next, before.revision()); }
        } catch (DuplicateKeyException conflict) {
            throw new ApiException(ErrorCode.DUPLICATE_NAME, Map.of("field", "key"));
        } catch (DataAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof MongoException mongo && mongo.getCode() == 112) { throw new ApiException(ErrorCode.STALE_VERSION); }
            }
            throw failure;
        }
        events.publish(new ClubPageChanged(next.clubId(), next.key(), now, Map.of("key", next.key(), "version", version, "active", next.active()),
                actor, actor == null ? DomainEvent.Origin.SYSTEM : DomainEvent.Origin.BACKOFFICE));
        return new Written(next.key(), Map.of("version", version, "active", next.active()));
    }
}
