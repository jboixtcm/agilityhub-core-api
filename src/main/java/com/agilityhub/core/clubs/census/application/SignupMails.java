package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.CensusEvent;
import com.agilityhub.core.identity.application.SignupLinks;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/**
 * The two signup e-mails that carry an S01 magic link (a credential), which stay on identity's SYSTEM path when E7-T02 moved
 * every other signup notice to the S11 engine (`CensusNotificationFacts`): the welcome link of N-02 (`MemberValidated`;
 * its APP copy is the engine's) and N-39 «Verifica que ets tu» (`SignupRecognitionRequested`, capped per recipient,
 * R-04-20). Both render in `Account.locale` with the signup's as fallback (S04 §8). The consumer beans keep their E3
 * names (`signupWelcomeMail`, `signupRecognitionMail`): they are the outbox consumer ids.
 */
@Service
public class SignupMails {
    private final CensusAccess access; private final SignupLinks links; private final SignupRecipientCap cap;
    public SignupMails(CensusAccess access, SignupLinks links, SignupRecipientCap cap) { this.access = access; this.links = links; this.cap = cap; }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliver(String eventId, CensusEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            var member = access.members.findById(string(event.payload().get("memberId"))).orElse(null);
            if (member == null || member.erasedAt != null) { return; }
            var variables = object("member_first_name", member.firstName, "gender", member.gender, "club_name", access.config().club().name(),
                    "locale", string(map(member.signup).getOrDefault("locale", access.config().club().defaultLocale())));
            switch (event.type()) {
                case "MemberValidated" -> links.send(eventId, member.accountId, true, variables);
                case "SignupRecognitionRequested" -> { if (cap.admitted(eventId, event.clubId(), event.type(), member.accountId)) { links.send(eventId, member.accountId, false, variables); } }
                default -> throw new IllegalArgumentException("Unsupported signup mail");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        @Bean DomainEventHandler<CensusEvent> signupWelcomeMail(SignupMails service) { return handler("MemberValidated", service, null); }
        @Bean DomainEventHandler<CensusEvent> signupRecognitionMail(SignupMails service, SignupRecipientCap cap) { return handler("SignupRecognitionRequested", service, cap); }
        private DomainEventHandler<CensusEvent> handler(String type, SignupMails service, SignupRecipientCap cap) {
            return new DomainEventHandler<>() {
                public String eventType() { return type; }
                public Class<CensusEvent> eventClass() { return CensusEvent.class; }
                public void handle(String id, CensusEvent event) {
                    service.deliver(id, event);
                    if (cap != null) { cap.retainAfterCommit(id, event.clubId(), event.type()); }
                }
            };
        }
    }
}
