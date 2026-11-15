package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.persistence.AnnouncementRepository;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * COMM-07 (owner decision of 13 Nov 2026, S11 R-11-13): «Reenvia els fallits» of an announcement. In one transaction, every
 * e-mail of the batch that ended `FAILED` goes back to `QUEUED`, due now and with a fresh R-11-09 budget; the dispatcher's poll
 * sends them at the announcements' pace, with every check of a send (a suppressed address ends `SKIPPED_NO_CONTACT`; an
 * announcement has no staleness check, so a member who left or turned the e-mail off since the send is still written to). A
 * delivery an erasure left without a target stays `FAILED`. Only e-mail: the APP copy never fails, an SMS or a push is not resent. An
 * `ANNOUNCEMENT_RESENT` audit entry on the batch's template, `details {batchId, resent}`, when at least one was put back; with
 * none there is nothing to resend and nothing is written. Another club's or an unknown batch is `NOT_FOUND`.
 */
@Service
public class AnnouncementResends {
    private final AnnouncementRepository announcements; private final NotificationRepository notifications; private final AuditWriter audit;
    private final Clock clock;

    public AnnouncementResends(AnnouncementRepository announcements, NotificationRepository notifications, AuditWriter audit, Clock clock) {
        this.announcements = announcements; this.notifications = notifications; this.audit = audit; this.clock = clock;
    }

    /** What a resend put back: the batch and how many failed e-mails. */
    public record Resend(String templateId, String batchId, int resent) {
        Map<String, Object> details() {
            var details = new LinkedHashMap<String, Object>(); details.put("batchId", batchId); details.put("resent", resent);
            return details;
        }
    }

    @Transactional
    public Resend resendFailed(String batchId) {
        var batch = announcements.findById(batchId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        int resent = Math.toIntExact(notifications.requeueFailedAnnouncementEmails(batch.batchId(), clock.instant()));
        var result = new Resend(batch.templateId(), batch.batchId(), resent);
        if (resent > 0) {
            audit.write(new AuditCommand(AuditAction.ANNOUNCEMENT_RESENT, "MessageTemplate", batch.templateId(), null, null, null, null), result.details());
        }
        return result;
    }
}
