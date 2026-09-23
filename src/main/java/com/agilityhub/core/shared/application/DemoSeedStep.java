package com.agilityhub.core.shared.application;

import java.time.LocalDate;
import java.util.*;

/**
 * Local demo data contributed by one bounded context after the census demo (E4-T05). Steps run in {@link #order()}
 * inside the single demo-planning transaction and write only through their context's application services.
 */
public interface DemoSeedStep {
    /** The seed file, the seed, the club-local Monday every dated item is relative to, the acting admin, and the census
     * members linked to the seed login accounts (kept free of demo registrations so front tests can act with them). */
    record Input(Map<String, Object> specification, long seed, LocalDate weekStart, String adminAccountId, Set<String> loginMemberIds) {
        public Input { specification = Collections.unmodifiableMap(new LinkedHashMap<>(specification)); loginMemberIds = Set.copyOf(loginMemberIds); }
    }
    int order();
    /** Returns the created-item counts of this step. */
    Map<String, Integer> apply(Input input);
}
