package com.agilityhub.core.platform.application.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What the census tells the erasure about one member of the current club (S14 R-14-14/R-14-15): its account, number and leave,
 * and the dogs whose data goes with it. `erasedAt` set = already pseudonymised (R-14-15 is idempotent on it).
 */
public record ErasureMember(String id, String accountId, Integer memberNumber, String status, LocalDate leaveDate, Instant leftAt,
        String leftReason, Instant erasedAt, List<String> dogIds) {
    public ErasureMember { dogIds = dogIds == null ? List.of() : List.copyOf(dogIds); }
    public boolean left() { return "LEFT".equals(status); }
    public boolean erased() { return erasedAt != null; }
}
