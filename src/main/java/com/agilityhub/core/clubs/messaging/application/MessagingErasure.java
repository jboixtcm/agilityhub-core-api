package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.persistence.MessagingErasureRepository;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.MemberDataPackage;
import com.agilityhub.core.platform.application.privacy.MemberDataSource;
import org.springframework.stereotype.Component;

/**
 * S14 WP-14-D, S11: the notification log keeps an erased member's notices without their content (R-14-15), with the account's
 * pseudonym as address; E7-T04's announcements forget the member and their search text; the account's push subscriptions in the
 * club go (S14 §7: S11 deletes `PushSubscription`). Nothing can be sent to the member again: the census directory skips erased members.
 */
@Component
public class MessagingErasure implements ErasureStep, MemberDataSource {
    private final MessagingErasureRepository erasure;
    public MessagingErasure(MessagingErasureRepository erasure) { this.erasure = erasure; }
    @Override public ErasureEffect erase(ErasureSubject subject) {
        var effect = ErasureEffect.builder();
        effect.count("notifications", erasure.eraseNotifications(subject.memberIds(), subject.accountIds()));
        subject.pseudonyms().forEach((account, pseudonym) -> effect.count("notifications.recipientEmail", erasure.pseudonymiseAddresses(account, pseudonym)));
        effect.count("announcements", erasure.eraseAnnouncements(subject.memberIds()));
        return effect.count("push_subscriptions", erasure.deletePushSubscriptions(subject.accountIds())).build();
    }

    /** S14 R-14-13: `notifications[]` — code, title, body, channels and dates (never another recipient's). */
    @Override @SuppressWarnings("unchecked")
    public void contribute(ErasureMember member, MemberDataPackage data) {
        data.section("notifications", erasure.notifications(member.id(), member.accountId()).stream().map(document -> {
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("code", document.get("code")); row.put("title", document.get("title")); row.put("body", document.get("body"));
            var deliveries = (java.util.List<java.util.Map<String, Object>>) document.getOrDefault("deliveries", java.util.List.of());
            row.put("channels", deliveries.isEmpty() && document.get("channel") != null ? java.util.List.of(document.get("channel"))
                    : deliveries.stream().map(delivery -> delivery.get("channel")).toList());
            row.put("createdAt", document.get("createdAt")); row.put("readAt", document.get("readAt"));
            return (Object) MemberDataPackage.row(row);
        }).toList());
    }
}
