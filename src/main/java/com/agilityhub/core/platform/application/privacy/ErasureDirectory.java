package com.agilityhub.core.platform.application.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** S14 WP-14-D: the census side of the erasure, read in the current club (implemented by `clubs.census`). */
public interface ErasureDirectory {
    Optional<ErasureMember> member(String memberId);
    /** The members of the current club linked to the account (S14 R-14-14, `scope = ACCOUNT`). */
    List<ErasureMember> ofAccount(String accountId);
    /**
     * S14 R-14-16 (a) and (b), INC-10: the not-yet-erased `LEFT` members of the current club whose retention is over — a leave
     * with `leaveDate ≤ leftOnOrBefore`, and a rejected signup (`leftReason = SIGNUP_REJECTED`) with `leftAt ≤ rejectedOnOrBefore`.
     */
    List<ErasureMember> retentionDue(LocalDate leftOnOrBefore, Instant rejectedOnOrBefore);
}
