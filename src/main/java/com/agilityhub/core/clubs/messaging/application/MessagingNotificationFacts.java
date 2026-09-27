package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
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
 * («setembre de 2026»).
 */
@Service
public class MessagingNotificationFacts implements NotificationFactsPort {
    private final MemberDirectoryPort members;
    public MessagingNotificationFacts(MemberDirectoryPort members) { this.members = members; }

    @Override public Set<String> eventTypes() { return Set.of("EmailBounced", "SmsCapReached"); }

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
            default -> Optional.empty();
        };
    }
}
