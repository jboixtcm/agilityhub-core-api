package com.agilityhub.core.platform.domain.events;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.*;

/** Only the catalog's existing club events, with its clubId/diff payload. */
public record ConsoleEvent(String type,String clubId,Instant occurredAt,Map<String,Object> payload,String actorAccountId,String impersonatedMemberId,Origin origin) implements DomainEvent {
    public ConsoleEvent { if(!Set.of("ClubCreated","ClubUpdated","ClubModulesChanged","ClubStatusChanged").contains(type)) throw new IllegalArgumentException("Unknown club event"); }
    @Override public String aggregateType() { return "Club"; }
    @Override public String aggregateId() { return clubId; }
    public ConsoleEvent(String type,String clubId,Instant occurredAt,Map<String,Object> payload,String actorAccountId) {
        this(type,clubId,occurredAt,payload,actorAccountId,null,Origin.BACKOFFICE);
    }
}
