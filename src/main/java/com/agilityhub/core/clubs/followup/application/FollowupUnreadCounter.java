package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.dashboard.application.ports.FollowUpUnreadQuery;
import com.agilityhub.core.clubs.followup.persistence.FollowupItemRepository;
import com.agilityhub.core.clubs.followup.persistence.FollowupReadMarkRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * R-10-13 menu counter as the dashboard's {@link FollowUpUnreadQuery}: `GET /dashboard/counters.followUpUnread` (S14
 * R-14-08, asked only with TASKS on) is the same count as `GET /followup/unread-count` ({@link FollowupService}).
 */
@Service
public class FollowupUnreadCounter implements FollowUpUnreadQuery {
    private final FollowupItemRepository items; private final FollowupReadMarkRepository marks;
    public FollowupUnreadCounter(FollowupItemRepository items, FollowupReadMarkRepository marks) { this.items = items; this.marks = marks; }

    /** The account's visible rows that are unread, one count over `{clubId, activityAt}`. */
    static long unread(FollowupItemRepository items, FollowupReadMarkRepository marks, String accountId) {
        var mark = marks.find(accountId).orElse(null);
        return items.unread(accountId, mark == null ? null : mark.readAllAt(), mark == null || mark.readItemIds() == null ? List.of() : mark.readItemIds());
    }
    @Override public int count(String clubId, String accountId) {
        if (!clubId.equals(TenantContext.require())) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
        return (int) Math.min(Integer.MAX_VALUE, unread(items, marks, accountId));
    }
}
