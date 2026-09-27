package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationEventEnvelope;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.shared.application.DomainEventHandler;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The outbox consumer of one catalog event type (S11 §6 `onEvent`): registered once per distinct `eventTypes[]` value of
 * the templated R1 codes, with the durable bean name `notifications.<EventType>` (the outbox dispatcher's consumer id —
 * never rename one). The engine is looked up lazily so the handlers exist before it.
 */
public final class NotificationEventHandler implements DomainEventHandler<NotificationEventEnvelope> {
    public static final String PREFIX = "notifications.";
    private final String type; private final ObjectProvider<NotificationEngine> engine;

    public NotificationEventHandler(String type, ObjectProvider<NotificationEngine> engine) {
        this.type = Objects.requireNonNull(type); this.engine = engine;
    }

    /** The event types the engine consumes: those of the catalog's R1 codes that have a template (SYSTEM codes have their own path). */
    public static List<String> eventTypes() {
        return NotificationCatalog.specs().stream().filter(spec -> spec.stage() == NotificationSpec.Stage.R1 && spec.templated())
                .flatMap(spec -> spec.eventTypes().stream()).distinct().sorted().toList();
    }

    @Override public String eventType() { return type; }
    @Override public Class<NotificationEventEnvelope> eventClass() { return NotificationEventEnvelope.class; }
    @Override public void handle(String eventId, NotificationEventEnvelope event) { engine.getObject().handle(eventId, event); }
}
