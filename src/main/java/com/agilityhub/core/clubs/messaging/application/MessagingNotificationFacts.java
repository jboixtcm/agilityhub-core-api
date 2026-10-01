package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.persistence.Announcement;
import com.agilityhub.core.clubs.messaging.persistence.AnnouncementRepository;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * S11's own events explained to the engine (E7-T02): N-51 `EmailBounced{memberId, email, type}` → the ADMINS, with the
 * member's name and the address, action OPEN_MEMBER; N-49 `SmsCapReached{month, cap}` → the ADMINS, `month` written out
 * («setembre de 2026»). E7-T04: N-24 `AnnouncementSent{templateId, batchId, recipientCount, filters}` → one `MEMBER`
 * notification per member of the stored batch ({@link Announcement#memberIds()}, frozen at the send), rendered with the
 * template it was sent with (N-24's or a `CUSTOM` one); a batch this club does not hold is no notice.
 */
@Service
public class MessagingNotificationFacts implements NotificationFactsPort {
    private final MemberDirectoryPort members; private final AnnouncementRepository announcements;
    public MessagingNotificationFacts(MemberDirectoryPort members, AnnouncementRepository announcements) {
        this.members = members; this.announcements = announcements;
    }

    @Override public Set<String> eventTypes() { return Set.of("EmailBounced", "SmsCapReached", "AnnouncementSent"); }

    @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
        return switch (trigger.type()) {
            case "EmailBounced" -> {
                String memberId = trigger.text("memberId");
                String name = memberId == null ? "" : members.find(memberId).map(MemberContact::displayName).orElse("");
                yield Optional.of(NotificationFacts.builder().noMembers().subject(NotificationSubject.member(memberId))
                        .value("member_name", name).value("email", trigger.text("email")).build());
            }
            case "SmsCapReached" -> {
                Object month;
                try { month = YearMonth.parse(trigger.text("month")); } catch (DateTimeParseException | NullPointerException unreadable) { month = trigger.text("month"); }
                yield Optional.of(NotificationFacts.builder().value("month", month).value("cap", trigger.number("cap", 0)).build());
            }
            case "AnnouncementSent" -> {
                var batch = trigger.text("batchId") == null ? null : announcements.findById(trigger.text("batchId")).orElse(null);
                if (batch == null) { yield Optional.empty(); }
                var builder = NotificationFacts.builder().template(batch.templateId()).noMembers();
                batch.memberIds().forEach(memberId -> builder.member(memberId, null));
                yield Optional.of(builder.build());
            }
            default -> Optional.empty();
        };
    }
}
