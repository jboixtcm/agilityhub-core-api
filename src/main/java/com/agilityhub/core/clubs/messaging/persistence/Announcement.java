package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One «Enviar comunicat» (S11 R-11-13, E7-T04): the batch of a manual send of the N-24 template or a `CUSTOM` one. S11 §3
 * lists three own collections; this fourth one is the batch the log and the replay rest on: which template, how the admin
 * chose the recipients (`filters` and `q` of `GET /members`, or an explicit selection), and the members it was sent to,
 * frozen at the send (`memberIds`, so the engine notifies exactly the `recipientCount` the admin was answered). Never
 * deleted; `id` = `batchId`.
 *
 * @param selection `FILTERS` (the list's filters and search) or `MEMBERS` (an explicit `memberIds[]` selection)
 */
@Document("announcements")
public record Announcement(@Id String id, String clubId, String templateId, String selection, List<String> filters, String q, List<String> memberIds,
        int recipientCount, String actorAccountId, Instant createdAt) implements TenantEntity {
    public static final String FILTERS = "FILTERS", MEMBERS = "MEMBERS";
    public Announcement {
        Objects.requireNonNull(templateId); Objects.requireNonNull(selection);
        filters = filters == null ? List.of() : List.copyOf(filters);
        memberIds = memberIds == null ? List.of() : List.copyOf(memberIds);
    }
    public String batchId() { return id; }
    @Override public String toString() { return "Announcement[batchId=" + id + ", templateId=" + templateId + ", recipientCount=" + recipientCount + "]"; }
}
