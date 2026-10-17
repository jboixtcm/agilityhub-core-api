package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ConsoleTransactions;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Shared fixtures of the S14 privacy unit tests: a Madrid club, a transaction that runs its work, requests. */
final class PrivacyFixtures {
    private PrivacyFixtures() { }
    static ClubConfig config(String clubId) {
        var club = new ClubConfig.ClubView(clubId, clubId, "Example Club", List.of("ca", "es", "en"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        return new ClubConfig(club, Map.of("rgpd.erasureMinDaysAfterLeave", 30), Set.of(), null, Map.of());
    }
    @SuppressWarnings("unchecked")
    static ConsoleTransactions transactions() {
        var transactions = mock(ConsoleTransactions.class);
        when(transactions.write(any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(0)).get());
        return transactions;
    }
    static ErasureRequest request(String id, String scope, String status, List<String> members, List<String> clubs, List<String> erasedClubs, String account) {
        return new ErasureRequest(id, "MEMBER".equals(scope) ? clubs.getFirst() : null, scope, account, members, clubs, "ADMIN", "admin-account",
                Instant.parse("2026-09-05T08:00:00Z"), null, status, Instant.parse("2026-09-29T22:00:00Z"), List.of(), null, null, null, List.of(),
                erasedClubs, false, true, null, 3);
    }
}
