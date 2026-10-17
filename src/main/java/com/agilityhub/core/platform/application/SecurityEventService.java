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
    private final com.agilityhub.core.platform.application.privacy.ErasurePseudonyms hashes;

    public SecurityEventService(SecurityEventRepository events, SecurityRequestProvider requests, Clock clock,
            com.agilityhub.core.platform.application.privacy.ErasurePseudonyms hashes) {
        this.events = events; this.requests = requests; this.clock = clock; this.hashes = hashes;
    }

    @Override public void record(Type type, String accountId, String clubId) { record(type, accountId, clubId, null, Map.of()); }

    /** S14 R-14-17: never the address, only its keyed `emailHash`; the request's route family and user agent. */
    @Override public void record(Type type, String accountId, String clubId, String email, Map<String, String> details) {
        var request = requests.current();
        var all = new java.util.LinkedHashMap<String, String>(details == null ? Map.of() : details);
        all.put("route", request.route());
        events.append(new SecurityEvent(UUID.randomUUID().toString(), clock.instant(), type, accountId, clubId,
                request.ip(), request.route(), all, hashes.emailHash(email), request.userAgent()));
    }
}
