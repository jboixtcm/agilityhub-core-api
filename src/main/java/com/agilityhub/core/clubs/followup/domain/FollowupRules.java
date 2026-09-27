package com.agilityhub.core.clubs.followup.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** S10 follow-up rules without I/O: the D14 excerpt (§3), the unread formula and the read-mark pruning (R-10-13). */
public final class FollowupRules {
    /** S10 §3 `FollowupItem.textExcerpt` (≤ 120), also N-20/N-21's `task_excerpt` (§8). */
    public static final int EXCERPT_LENGTH = 120;
    private FollowupRules() { }

    /**
     * The first 120 characters of a task or note text on one line: runs of white space (new lines too) become one space and
     * the ends are trimmed; a longer text keeps 119 characters and «…». Counted in code points, so no emoji is ever cut in
     * half. Only the excerpt is shortened: the text itself is member content, stored as written.
     */
    public static String excerpt(String text) {
        if (text == null) { return ""; }
        String line = text.strip().replaceAll("\\s+", " ");
        if (line.codePointCount(0, line.length()) <= EXCERPT_LENGTH) { return line; }
        return line.substring(0, line.offsetByCodePoints(0, EXCERPT_LENGTH - 1)).stripTrailing() + "…";
    }

    /**
     * R-10-13: `unread(item, me) = item.activityAt > readAllAt(me) ∧ item.id ∉ readItemIds(me) ∧ item.authorAccountId ≠ me`.
     * No read-all yet (`readAllAt` null) leaves the first condition true.
     */
    public static boolean unread(String itemId, Instant activityAt, String authorAccountId, Instant readAllAt, Collection<String> readItemIds, String me) {
        return (readAllAt == null || activityAt != null && activityAt.isAfter(readAllAt))
                && (readItemIds == null || !readItemIds.contains(itemId)) && !Objects.equals(authorAccountId, me);
    }

    /**
     * R-10-13 «es poden podar els ids amb `activityAt < readAllAt`»: the read ids a mark still needs, those whose row moved
     * after the read-all (the others are read by `readAllAt` already, and a row that no longer exists needs nothing).
     */
    public static List<String> pruned(Collection<String> readItemIds, Map<String, Instant> activityById, Instant readAllAt) {
        return readItemIds.stream().distinct().filter(id -> activityById.containsKey(id)
                && (readAllAt == null || activityById.get(id) != null && activityById.get(id).isAfter(readAllAt))).toList();
    }
}
