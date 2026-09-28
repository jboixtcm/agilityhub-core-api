package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.Club;
import java.time.YearMonth;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * S11 R-11-06 SMS counter of `Club.usage` (`smsSentMonth` in the club-local month `smsMonthKey` = `yyyyMM`), written only
 * with conditional atomic updates, never by replacing the club: {@link #reserve} counts one SMS before it is sent (at most
 * `cap` per month, so two senders at 999 never both pass), {@link #release} gives back the reservation of a send that
 * failed, and the first SMS of a new month restarts the count (S15 R-15-15 g). `smsCapNoticeMonth` records the month whose
 * cap notice (N-49) was already emitted, once per month.
 */
@Service
public class ClubSmsUsage {
    public static final String SENT = "usage.smsSentMonth", MONTH = "usage.smsMonthKey", NOTICE = "usage.smsCapNoticeMonth";
    private final MongoTemplate mongo;
    public ClubSmsUsage(MongoTemplate mongo) { this.mongo = mongo; }

    public static long key(YearMonth month) { return month.getYear() * 100L + month.getMonthValue(); }

    /** One more SMS of `month` for the club: `true` (and counted) below the cap, `false` at the cap. */
    public boolean reserve(String clubId, YearMonth month, long cap) {
        if (cap <= 0) { return false; }
        long key = key(month);
        var sameMonth = Query.query(Criteria.where("_id").is(clubId).and(MONTH).is(key).and(SENT).lt(cap));
        if (mongo.updateFirst(sameMonth, new Update().inc(SENT, 1L), Club.class).getModifiedCount() == 1) { return true; }
        var newMonth = Query.query(Criteria.where("_id").is(clubId).and(MONTH).ne(key));
        if (mongo.updateFirst(newMonth, new Update().set(MONTH, key).set(SENT, 1L), Club.class).getModifiedCount() == 1) { return true; }
        // Another sender restarted the month between both updates: count in it, still below the cap.
        return mongo.updateFirst(sameMonth, new Update().inc(SENT, 1L), Club.class).getModifiedCount() == 1;
    }
    /** Gives back one reservation of `month` (the provider refused or failed the SMS). */
    public void release(String clubId, YearMonth month) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(clubId).and(MONTH).is(key(month)).and(SENT).gt(0L)), new Update().inc(SENT, -1L), Club.class);
    }
    /** The SMS counted in `month` (0 when the stored month is another one). */
    public long sent(String clubId, YearMonth month) {
        var club = mongo.findById(clubId, Club.class);
        if (club == null || club.usage() == null) { return 0; }
        Long storedKey = club.usage().get("smsMonthKey"), storedSent = club.usage().get("smsSentMonth");
        return storedKey != null && storedKey == key(month) && storedSent != null ? storedSent : 0;
    }
    /** `true` exactly once per club and month: the first caller emits `SmsCapReached` (N-49 once per month). */
    public boolean firstCapNotice(String clubId, YearMonth month) {
        long key = key(month);
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(clubId).and(NOTICE).ne(key)), new Update().set(NOTICE, key), Club.class).getModifiedCount() == 1;
    }
}
