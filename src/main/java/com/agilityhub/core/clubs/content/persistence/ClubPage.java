package com.agilityhub.core.clubs.content.persistence;

import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("club_pages")
public record ClubPage(@Id String id, String clubId, String key, LocalizedText title, LocalizedText body,
        int version, Instant publishedAt, String publishedBy, boolean active, Change lastChange,
        List<History> history, long revision) implements TenantEntity {
    public ClubPage { history = List.copyOf(history); }
    public record Change(Instant at, String by) { }
    public record History(int version, Instant publishedAt, String by, LocalizedText title, LocalizedText body) { }
}
