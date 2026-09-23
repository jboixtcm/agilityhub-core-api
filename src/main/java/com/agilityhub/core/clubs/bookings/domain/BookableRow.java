package com.agilityhub.core.clubs.bookings.domain;

/**
 * S08 R-08-03 row state of screen 04, by priority: NOT_BOOKABLE (block, inactivity, leaving) → NOT_YET_OPEN →
 * PACK_EMPTY → WEEKLY_LIMIT_DONE (limit reached and nothing swappable) → WAITLIST_FULL / WAITLIST_OPEN / FULL →
 * BOOKABLE. `/me/bookable-classes` (E5-T06) builds its rows with this function; the hold keeps `limit.reached`.
 */
public final class BookableRow {
    public enum State { BOOKABLE, WAITLIST_OPEN, WAITLIST_FULL, FULL, WEEKLY_LIMIT_DONE, NOT_YET_OPEN, PACK_EMPTY, NOT_BOOKABLE }
    public enum NotBookable { BLOCKED, INACTIVITY, LEAVING }
    public record Input(NotBookable notBookable, boolean notYetOpen, boolean packEmpty, boolean limitDone,
            boolean full, boolean waitlistModule, int waiting, int waitlistMax) { }
    public record Row(State state, NotBookable reason) { }
    private BookableRow() { }
    public static Row resolve(Input in) {
        if (in.notBookable() != null) { return new Row(State.NOT_BOOKABLE, in.notBookable()); }
        if (in.notYetOpen()) { return new Row(State.NOT_YET_OPEN, null); }
        if (in.packEmpty()) { return new Row(State.PACK_EMPTY, null); }
        if (in.limitDone()) { return new Row(State.WEEKLY_LIMIT_DONE, null); }
        if (in.full()) {
            if (!in.waitlistModule()) { return new Row(State.FULL, null); }
            return new Row(in.waiting() >= in.waitlistMax() ? State.WAITLIST_FULL : State.WAITLIST_OPEN, null);
        }
        return new Row(State.BOOKABLE, null);
    }
}
