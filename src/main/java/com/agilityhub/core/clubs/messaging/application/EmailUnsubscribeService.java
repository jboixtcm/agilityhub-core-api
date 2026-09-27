package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.engine.UnsubscribeTokens;
import com.agilityhub.core.clubs.messaging.application.ports.MemberContactsWriterPort;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.domain.MessagingEvent;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S11 R-11-08 «Deixar de rebre aquests comunicats» (`POST /email-unsubscribes`, anonymous): a valid token of the host's
 * club turns `emailByCategory.CLUB_NEWS` off for its member and publishes `EmailUnsubscribed{memberId}`, in one
 * transaction; a second use changes nothing and publishes nothing. An expired or tampered token, another club's, or one
 * whose member no longer exists is `UNSUBSCRIBE_TOKEN_INVALID` (422, CATALEG_ERRORS rule 0).
 */
@Service
public class EmailUnsubscribeService {
    private final UnsubscribeTokens tokens; private final MemberDirectoryPort members; private final MemberContactsWriterPort writer;
    private final EventPublisher events; private final Clock clock;

    public EmailUnsubscribeService(UnsubscribeTokens tokens, MemberDirectoryPort members, MemberContactsWriterPort writer, EventPublisher events, Clock clock) {
        this.tokens = tokens; this.members = members; this.writer = writer; this.events = events; this.clock = clock;
    }

    @Transactional
    public void unsubscribe(String token) {
        String clubId = TenantContext.require();
        var claim = tokens.verify(token, clubId);
        if (members.find(claim.memberId()).isEmpty()) { throw new ApiException(ErrorCode.UNSUBSCRIBE_TOKEN_INVALID); }
        if (writer.unsubscribeClubNews(claim.memberId(), clock.instant())) {
            events.publish(new MessagingEvent(MessagingEvent.Kind.EmailUnsubscribed, clubId, claim.memberId(), clock.instant(),
                    Map.of("memberId", claim.memberId()), null, null, DomainEvent.Origin.APP));
        }
    }
}
