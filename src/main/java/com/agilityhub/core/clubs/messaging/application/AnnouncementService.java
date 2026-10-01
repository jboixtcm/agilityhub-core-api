package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.domain.MessagingEvent;
import com.agilityhub.core.clubs.messaging.domain.TemplateKind;
import com.agilityhub.core.clubs.messaging.domain.TemplateStatus;
import com.agilityhub.core.clubs.messaging.persistence.Announcement;
import com.agilityhub.core.clubs.messaging.persistence.AnnouncementRepository;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S11 R-11-13 «Enviar comunicat» (E7-T04), immediate and to members only (B18, S11 §13-6): only the N-24 template or a
 * `CUSTOM` one that is active (`TEMPLATE_NOT_SENDABLE` otherwise; an archived or unknown template is `NOT_FOUND`). The
 * recipients are an explicit selection (`memberIds[]`) or what `GET /members` lists with the same `filters[]` and `q`, as
 * given: no hidden status filter is added. A member who left is never a recipient: R-11-02 sends them only what an event
 * about them mandates (T-11-14), so the count is the count of the members the notices will reach. None → `NO_RECIPIENTS`.
 *
 * <p>A dry run only counts («S'enviarà a {n} abonats») and writes nothing. A real send, in one transaction: the
 * {@link Announcement} batch (its members frozen), `AnnouncementSent{templateId, batchId, recipientCount, filters}` on the
 * outbox and the `ANNOUNCEMENT_SENT` audit entry on the template with `{batchId, recipientCount, filters, selection}`. The
 * engine then creates one `MEMBER` notification per member (`dedupKey = {batchId}:{memberId}`) with that member's
 * preferences and the template's matrix. The `Idempotency-Key` replay of the same batch is the API's (`IdempotencyFilter`).</p>
 */
@Service
public class AnnouncementService {
    static final String N24 = "N-24";
    private final MessageTemplateRepository templates; private final AnnouncementRepository announcements; private final MemberDirectoryPort members;
    private final EventPublisher events; private final Clock clock;

    public AnnouncementService(MessageTemplateRepository templates, AnnouncementRepository announcements, MemberDirectoryPort members, EventPublisher events,
            Clock clock) {
        this.templates = templates; this.announcements = announcements; this.members = members; this.events = events; this.clock = clock;
    }

    /** `{memberIds[]}` (a selection) or `{filters[], q}` (the list's), never both nor neither (`VALIDATION_ERROR`). */
    public record Recipients(List<String> memberIds, List<String> filters, String q) {
        public Recipients {
            boolean selection = memberIds != null, list = filters != null || q != null;
            if (selection == list) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "recipients")); }
            memberIds = memberIds == null ? null : List.copyOf(new LinkedHashSet<>(memberIds));
            filters = filters == null ? (selection ? null : List.of()) : List.copyOf(filters);
        }
        String selection() { return memberIds != null ? Announcement.MEMBERS : Announcement.FILTERS; }
    }
    /** What the real send did; the audited fields are the entry's details. */
    public record Receipt(String templateId, @AuditField String batchId, @AuditField int recipientCount, @AuditField List<String> filters,
            @AuditField String selection) { }

    /** The dry run: how many members the send would reach. Nothing is written. */
    public int count(String templateId, Recipients recipients) {
        sendable(templateId);
        return recipients(recipients).size();
    }

    @Transactional
    @Audited(action = AuditAction.ANNOUNCEMENT_SENT, entityType = "'MessageTemplate'", entity = "#result.templateId()")
    public Receipt send(String templateId, Recipients recipients) {
        var template = sendable(templateId);
        var reached = recipients(recipients);
        String clubId = TenantContext.require(), batchId = UUID.randomUUID().toString();
        var user = CurrentUser.current();
        var filters = recipients.filters() == null ? List.<String>of() : recipients.filters();
        announcements.insert(new Announcement(batchId, clubId, template.id(), recipients.selection(), filters, recipients.q(),
                reached.stream().map(MemberContact::memberId).toList(), reached.size(), user == null ? null : user.accountId(), clock.instant()));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("templateId", template.id()); payload.put("batchId", batchId); payload.put("recipientCount", reached.size()); payload.put("filters", filters);
        events.publish(new MessagingEvent(MessagingEvent.Kind.AnnouncementSent, clubId, batchId, clock.instant(), payload,
                user == null ? null : user.accountId(), user == null || user.impersonation() == null ? null : user.impersonation().memberId(),
                user == null ? DomainEvent.Origin.SYSTEM : Objects.requireNonNullElse(user.origin(), DomainEvent.Origin.BACKOFFICE)));
        return new Receipt(template.id(), batchId, reached.size(), filters, recipients.selection());
    }

    /** N-24 or an active `CUSTOM` template of the club; an archived one is gone for D9 and for the send dialog. */
    MessageTemplate sendable(String templateId) {
        var template = templates.findById(templateId).filter(t -> t.status() != TemplateStatus.ARCHIVED).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        boolean eligible = template.kind() == TemplateKind.CUSTOM || N24.equals(template.code());
        if (!eligible || template.status() != TemplateStatus.ACTIVE || !template.enabled()) { throw new ApiException(ErrorCode.TEMPLATE_NOT_SENDABLE); }
        return template;
    }

    /** The members the send reaches, once each, in the list's order; members who left are not among them (R-11-02). */
    List<MemberContact> recipients(Recipients recipients) {
        var found = recipients.memberIds() != null ? members.byIds(recipients.memberIds()) : members.byFilters(recipients.filters(), recipients.q());
        var unique = new LinkedHashMap<String, MemberContact>();
        found.stream().filter(contact -> contact.memberId() != null && !contact.left()).forEach(contact -> unique.putIfAbsent(contact.memberId(), contact));
        if (unique.isEmpty()) { throw new ApiException(ErrorCode.NO_RECIPIENTS); }
        return List.copyOf(unique.values());
    }
}
