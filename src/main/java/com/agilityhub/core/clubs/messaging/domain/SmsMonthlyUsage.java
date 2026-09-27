package com.agilityhub.core.clubs.messaging.domain;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The club's SMS counter of R-11-06 inside `Club.usage` (a `Map<String, Long>`, shape unchanged): `smsSentMonth` counts the
 * SMS sent in the month `smsMonthKey` (`yyyyMM` as a number, e.g. 202609). The month is the club-local one (S15 R-15-15
 * step g): a stored key of another month reads as 0, so the first SMS of a new month restarts the count.
 */
public record SmsMonthlyUsage(long sent, long monthKey) {
    public static final String SENT = "smsSentMonth", MONTH = "smsMonthKey";

    public static long monthKey(YearMonth month) { return month.getYear() * 100L + month.getMonthValue(); }

    /** The count of the club-local `month`: the stored one when it is that month's, otherwise 0. */
    public static SmsMonthlyUsage of(Map<String, Long> usage, YearMonth month) {
        long key = monthKey(month);
        Long storedKey = usage == null ? null : usage.get(MONTH), storedSent = usage == null ? null : usage.get(SENT);
        return new SmsMonthlyUsage(storedKey != null && storedKey == key && storedSent != null ? storedSent : 0, key);
    }

    /** `count ≥ messaging.sms.monthlyCap` → `SKIPPED_CAP` (R-11-06, T-11-07: 999 sends, 1000 stops). */
    public boolean reached(long cap) { return sent >= cap; }

    /** The two usage keys after one more SMS in this month. */
    public Map<String, Long> afterOneMore() {
        var keys = new LinkedHashMap<String, Long>();
        keys.put(SENT, sent + 1); keys.put(MONTH, monthKey);
        return keys;
    }
}
