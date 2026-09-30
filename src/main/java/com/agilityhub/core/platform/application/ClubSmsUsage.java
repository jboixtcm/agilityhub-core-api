package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.Club;
import java.time.YearMonth;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
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
 *
 * <p>The stored month and the notice marker only move forward (E7-T05): a sender that computed the old month and arrives
 * after another one began the new month counts in the new month (the SMS goes out now), never restarting it, and its
 * old-month notice never overwrites the new month's marker.</p>
 */
@Service
public class ClubSmsUsage {
    public static final String SENT = "usage.smsSentMonth", MONTH = "usage.smsMonthKey", NOTICE = "usage.smsCapNoticeMonth";
    private final MongoTemplate mongo;
    public ClubSmsUsage(MongoTemplate mongo) { this.mongo = mongo; }

    public static long key(YearMonth month) { return month.getYear() * 100L + month.getMonthValue(); }
    static YearMonth month(long key) { return YearMonth.of((int) (key / 100), (int) (key % 100)); }

    /**
     * One SMS asked for in `month`: `granted` when it was counted, `month` the month it counts in (or whose cap it met) — the
     * asked one, or the club's later stored month when another sender already began it. Release a granted reservation
     * with that month.
     */
    public record Reservation(YearMonth month, boolean granted) { }

    /** One more SMS of `month` for the club: counted below the cap, refused at the cap (see {@link Reservation}). */
    public Reservation reserve(String clubId, YearMonth month, long cap) {
        if (cap <= 0) { return new Reservation(month, false); }
        long key = key(month);
        for (int round = 0; round < 2; round++) {
            // The stored month when it is this one or a later one: a sender late on its month never moves the counter back.
            var counted = mongo.findAndModify(Query.query(Criteria.where("_id").is(clubId).and(MONTH).gte(key).and(SENT).lt(cap)), new Update().inc(SENT, 1L),
                    FindAndModifyOptions.options().returnNew(true), Document.class, mongo.getCollectionName(Club.class));
            if (counted != null) { return new Reservation(storedMonth(counted).orElse(month), true); }
            // The first SMS of a later month restarts the count; an earlier or missing month only.
            var earlier = Query.query(Criteria.where("_id").is(clubId).orOperator(Criteria.where(MONTH).is(null), Criteria.where(MONTH).lt(key)));
            if (mongo.updateFirst(earlier, new Update().set(MONTH, key).set(SENT, 1L), Club.class).getModifiedCount() == 1) { return new Reservation(month, true); }
            // Another sender began this month (or a later one) between both updates: count in it, still below the cap.
        }
        var stored = Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("_id").is(clubId)), Document.class, mongo.getCollectionName(Club.class)))
                .flatMap(ClubSmsUsage::storedMonth);
        return new Reservation(stored.filter(later -> later.isAfter(month)).orElse(month), false);
    }
    /** Gives back one reservation of `month` (the provider refused or failed the SMS); nothing once a later month began. */
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
    /**
     * `true` exactly once per club and month: the first caller emits `SmsCapReached` (N-49 once per month). The marker only
     * moves forward: a notice of a month before the marked one is refused.
     */
    public boolean firstCapNotice(String clubId, YearMonth month) {
        long key = key(month);
        var earlier = Query.query(Criteria.where("_id").is(clubId).orOperator(Criteria.where(NOTICE).is(null), Criteria.where(NOTICE).lt(key)));
        return mongo.updateFirst(earlier, new Update().set(NOTICE, key), Club.class).getModifiedCount() == 1;
    }

    private static Optional<YearMonth> storedMonth(Document club) {
        return Optional.ofNullable(club.get("usage", Document.class)).map(usage -> usage.get("smsMonthKey")).filter(Number.class::isInstance)
                .map(key -> month(((Number) key).longValue()));
    }
}
