package com.agilityhub.core.clubs.messaging.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * S11 §3 `NotificationPreference`, embedded in `Member.notificationPreferences` (a map owned by the census): the typed view
 * the messaging context reads and writes. An absent block, or an absent key, reads as the product default (the mockup of
 * 12): e-mail OFF for `OPERATIONAL`, ON for `PERSONAL`, `CLUB_CHANGES` and `CLUB_NEWS` (the 4th row, S11 §13-2), no
 * reminder («Mai»), push of club news ON. Reading never persists the defaults; keys this record does not know (the Playoff
 * migration's `essentialOnly`, S18) are kept by {@link #toDocument(Map)}.
 *
 * @param reminderMinutesBefore `null` = «Mai»; otherwise one of `messaging.reminderOptionsMinutes` (R-11-04)
 */
public record NotificationPreference(Map<NotificationCategory, Boolean> emailByCategory, Integer reminderMinutesBefore, boolean pushClubNews,
        Instant updatedAt, String updatedByAccountId) {
    static final String EMAIL_BY_CATEGORY = "emailByCategory", REMINDER = "reminderMinutesBefore", PUSH_CLUB_NEWS = "pushClubNews",
            UPDATED_AT = "updatedAt", UPDATED_BY = "updatedByAccountId", ESSENTIAL_ONLY = "essentialOnly";
    public static final NotificationPreference DEFAULTS = new NotificationPreference(Map.of(), null, true, null, null);

    public NotificationPreference {
        var complete = new EnumMap<NotificationCategory, Boolean>(NotificationCategory.class);
        for (var category : NotificationCategory.values()) {
            if (category.templated()) { complete.put(category, emailByCategory.getOrDefault(category, category != NotificationCategory.OPERATIONAL)); }
        }
        emailByCategory = Collections.unmodifiableMap(complete);
    }

    /** Whether the member wants e-mail for the category; `SYSTEM` ignores preferences (T-11-03). */
    public boolean email(NotificationCategory category) { return category == NotificationCategory.SYSTEM || emailByCategory.get(category); }

    /**
     * The typed view of the stored block; `null` or an empty map is {@link #DEFAULTS}. Playoff's «només avisos essencials»
     * (`essentialOnly = true`, S18 mapping row 30) reads as `CLUB_NEWS` without e-mail nor push (ruling E66); a key the member
     * saved later wins.
     */
    public static NotificationPreference of(Map<String, ?> stored) {
        if (stored == null || stored.isEmpty()) { return DEFAULTS; }
        var email = new EnumMap<NotificationCategory, Boolean>(NotificationCategory.class);
        boolean essentialOnly = Boolean.TRUE.equals(stored.get(ESSENTIAL_ONLY));
        if (essentialOnly) { email.put(NotificationCategory.CLUB_NEWS, false); }
        if (stored.get(EMAIL_BY_CATEGORY) instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                for (var category : NotificationCategory.values()) {
                    if (category.templated() && category.name().equals(key) && value instanceof Boolean flag) { email.put(category, flag); }
                }
            });
        }
        Integer reminder = stored.get(REMINDER) instanceof Number minutes ? minutes.intValue() : null;
        boolean push = stored.get(PUSH_CLUB_NEWS) instanceof Boolean flag ? flag : !essentialOnly;
        Instant at = stored.get(UPDATED_AT) instanceof Instant instant ? instant : stored.get(UPDATED_AT) instanceof java.util.Date date ? date.toInstant() : null;
        return new NotificationPreference(email, reminder, push, at, stored.get(UPDATED_BY) instanceof String by ? by : null);
    }

    /** The block to store: this record's keys over the stored ones (unknown keys stay). */
    public Map<String, Object> toDocument(Map<String, ?> stored) {
        var document = new LinkedHashMap<String, Object>();
        if (stored != null) { document.putAll(stored); }
        var email = new LinkedHashMap<String, Boolean>();
        emailByCategory.forEach((category, flag) -> email.put(category.name(), flag));
        document.put(EMAIL_BY_CATEGORY, email);
        document.put(REMINDER, reminderMinutesBefore);
        document.put(PUSH_CLUB_NEWS, pushClubNews);
        document.put(UPDATED_AT, updatedAt);
        document.put(UPDATED_BY, updatedByAccountId);
        return document;
    }

    public NotificationPreference changedBy(String accountId, Instant at) {
        return new NotificationPreference(emailByCategory, reminderMinutesBefore, pushClubNews, Objects.requireNonNull(at), accountId);
    }
}
