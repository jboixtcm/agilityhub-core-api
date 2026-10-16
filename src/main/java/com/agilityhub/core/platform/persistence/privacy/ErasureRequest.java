package com.agilityhub.core.platform.persistence.privacy;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S14 §3 `ErasureRequest` (context `platform`, `clubId` null for an account's request). `live` is set while the request is
 * `SCHEDULED`, `BLOCKED` or `ERASING`: the unique partial index on `{memberIds}` keeps one live request per member (§3).
 * `erasedClubIds` records the clubs already pseudonymised (one transaction per club, R-14-15); `accountErased` that the account
 * itself was. `version` is the optimistic lock of T-14-24.
 */
@Document("erasure_requests")
public record ErasureRequest(@Id String id, String clubId, String scope, String accountId, List<String> memberIds, List<String> clubIds,
        String source, String requestedByAccountId, Instant requestedAt, String reason, String status, Instant executeAt,
        List<String> blockReasons, Instant erasedAt, Instant cancelledAt, String cancelledByAccountId, List<String> filesPending,
        List<String> erasedClubIds, boolean accountErased, Boolean live, Instant lastAttemptAt, long version) {
    public ErasureRequest {
        memberIds = memberIds == null ? List.of() : List.copyOf(memberIds);
        clubIds = clubIds == null ? List.of() : List.copyOf(clubIds);
        blockReasons = blockReasons == null ? List.of() : List.copyOf(blockReasons);
        filesPending = filesPending == null ? List.of() : List.copyOf(filesPending);
        erasedClubIds = erasedClubIds == null ? List.of() : List.copyOf(erasedClubIds);
    }
    public static final List<String> LIVE = List.of("SCHEDULED", "BLOCKED", "ERASING");
}
