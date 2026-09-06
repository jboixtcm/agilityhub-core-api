package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.SecurityEvent;
import com.agilityhub.core.platform.persistence.SecurityEventRepository;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.SecurityRequestProvider;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SecurityEventService implements SecurityEvents {
    private final SecurityEventRepository events;
    private final SecurityRequestProvider requests;
    private final Clock clock;

    public SecurityEventService(SecurityEventRepository events, SecurityRequestProvider requests, Clock clock) {
        this.events = events; this.requests = requests; this.clock = clock;
    }

    @Override public void record(Type type, String accountId, String clubId) {
        var request = requests.current();
        events.append(new SecurityEvent(UUID.randomUUID().toString(), clock.instant(), type, accountId, clubId,
                request.ip(), request.route(), Map.of("route", request.route())));
    }
}
