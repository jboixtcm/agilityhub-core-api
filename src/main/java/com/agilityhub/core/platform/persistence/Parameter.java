package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.platform.domain.ImmutableValues;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("parameters")
public record Parameter(@Id String id, String clubId, String key, Object value, String type, String scope,
                        String scopeRef, List<History> history, @Version Long version, Instant updatedAt) implements TenantEntity {
    public Parameter { value = ImmutableValues.freeze(value); history = List.copyOf(history); }
    public record History(Object value, Instant changedAt, String changedByAccountId, String reason) {
        public History { value = ImmutableValues.freeze(value); }
    }
}
