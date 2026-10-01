package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.TemplateColor;
import com.agilityhub.core.clubs.messaging.domain.TemplateIcon;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One «Enviar comunicat» (S11 R-11-13, E7-T04): the batch of a manual send of the N-24 template or a `CUSTOM` one. S11 §3
 * lists three own collections; this fourth one is the batch the log and the replay rest on: which template, and that template
 * as it was sent ({@link SentTemplate}: the engine renders the batch with it, ruling E82), how the admin chose the recipients
 * (`filters` and `q` of `GET /members`, or an explicit selection), and the members it was sent to, frozen at the send
 * (`memberIds`, so the engine notifies exactly the `recipientCount` the admin was answered). Never deleted; `id` = `batchId`.
 *
 * @param template  the template as it was sent; every send stores it (`null` only in a batch a database kept from before E7-T04's
 *                  round 2: the engine sends nothing for such a batch, never another text)
 * @param selection `FILTERS` (the list's filters and search) or `MEMBERS` (an explicit `memberIds[]` selection)
 */
@Document("announcements")
public record Announcement(@Id String id, String clubId, String templateId, SentTemplate template, String selection, List<String> filters, String q,
        List<String> memberIds, int recipientCount, String actorAccountId, Instant createdAt) implements TenantEntity {
    public static final String FILTERS = "FILTERS", MEMBERS = "MEMBERS";
    public Announcement {
        Objects.requireNonNull(templateId); Objects.requireNonNull(selection);
        filters = filters == null ? List.of() : List.copyOf(filters);
        memberIds = memberIds == null ? List.of() : List.copyOf(memberIds);
    }
    public String batchId() { return id; }
    @Override public String toString() { return "Announcement[batchId=" + id + ", templateId=" + templateId + ", recipientCount=" + recipientCount + "]"; }

    /**
     * The template of the batch as it was at the send (S11 R-11-13, ruling E82): its version, category, texts, icon, colour
     * and matrix. Archiving, disabling or editing the template after the `202` neither stops the batch nor changes its text.
     */
    public record SentTemplate(String id, Long version, String code, TemplateKind kind, NotificationCategory category, LocalizedText title, LocalizedText body,
            LocalizedText smsBody, TemplateIcon icon, TemplateColor color, Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix) {
        public SentTemplate { Objects.requireNonNull(id); }
        public static SentTemplate of(MessageTemplate template) {
            return new SentTemplate(template.id(), template.version(), template.code(), template.kind(), template.category(), template.title(), template.body(),
                    template.smsBody(), template.icon(), template.color(), template.matrix());
        }
        /** The frozen copy as the engine renders it: the template's id and version at the send, always sendable. */
        public MessageTemplate asTemplate(String clubId) {
            return new MessageTemplate(id, clubId, code, kind, category, title, body, smsBody, icon, color, matrix, true, false, false, TemplateStatus.ACTIVE, version,
                    null, null, null, null);
        }
    }
}
