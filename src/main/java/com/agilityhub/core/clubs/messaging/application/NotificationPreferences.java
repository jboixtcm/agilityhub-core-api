package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationPreference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The S11 preference rules other contexts need, over the stored `Member.notificationPreferences` block (the census owns
 * the map; messaging owns its meaning): the channels a `CLUB_CHANGES` notice takes (S07 D7's cancellation preview) and the
 * block after an unsubscribe (R-11-08). Static and side-effect free.
 */
public final class NotificationPreferences {
    private NotificationPreferences() { }

    /** R-11-03 for a `MEMBER` `CLUB_CHANGES` notice: APP always, EMAIL unless switched off, SMS when possible («+SMS» is fixed). */
    public static List<String> clubChangesChannels(Map<String, ?> stored, boolean hasEmail, boolean smsPossible) {
        var channels = new ArrayList<String>(); channels.add("APP");
        if (hasEmail && NotificationPreference.of(stored).email(NotificationCategory.CLUB_CHANGES)) { channels.add("EMAIL"); }
        if (smsPossible) { channels.add("SMS"); }
        return channels;
    }
    /** Whether the member still receives `CLUB_NEWS` by e-mail (the product default is yes). */
    public static boolean clubNewsEmail(Map<String, ?> stored) { return NotificationPreference.of(stored).email(NotificationCategory.CLUB_NEWS); }
    /** R-11-08: the stored block with `emailByCategory.CLUB_NEWS = false` (other keys kept); `byAccountId` null = the member's own link. */
    public static Map<String, Object> clubNewsOff(Map<String, ?> stored, Instant at) {
        var current = NotificationPreference.of(stored);
        var email = new EnumMap<NotificationCategory, Boolean>(current.emailByCategory());
        email.put(NotificationCategory.CLUB_NEWS, false);
        return new NotificationPreference(email, current.reminderMinutesBefore(), current.pushClubNews(), at, null).toDocument(stored);
    }
}
