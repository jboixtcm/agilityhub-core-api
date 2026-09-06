package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.shared.application.SecurityEvents.Type;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Global storage per MODEL_DADES_PLATAFORMA §0; optional club context grants no tenant access. */
@Document("security_events")
public record SecurityEvent(@Id String id, Instant at, Type type, String accountId, String clubId,
                            String ip, String route, Map<String, String> details) { }
