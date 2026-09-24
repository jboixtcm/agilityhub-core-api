package com.agilityhub.core.shared.application;

import java.time.LocalDate;
import java.util.*;

/**
 * Local demo data contributed by one bounded context after the census demo (E4-T05). Steps run in {@link #order()}
 * inside the single demo-planning transaction and write only through their context's application services.
 */
public interface DemoSeedStep {
    /** The seed file, the seed, the club-local Monday every dated item is relative to, the acting admin, the census
     * members linked to the seed login accounts (kept free of the E4 demo registrations so front tests can act with them),
     * every census member id in ordinal order (E5-T06 scenario references) and the club-local date of the run. */
    record Input(Map<String, Object> specification, long seed, LocalDate weekStart, String adminAccountId, Set<String> loginMemberIds,
            List<String> memberIds, LocalDate today) {
        public Input {
            specification = Collections.unmodifiableMap(new LinkedHashMap<>(specification)); loginMemberIds = Set.copyOf(loginMemberIds);
            memberIds = List.copyOf(memberIds);
        }
        /**
         * The E5-T06 `scenario` section, applied only when every day of the anchor week is still ahead
         * (`weekStart` on or after the run date): an empty map otherwise, and for seeds without one.
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> scenario() {
            var value = specification.get("scenario");
            return value instanceof Map<?, ?> map && !weekStart.isBefore(today) ? (Map<String, Object>) map : Map.of();
        }
        /** The census member id of an ordinal (the seed login accounts come first, in `accountEmails` order). */
        public String member(int ordinal) {
            if (ordinal < 0 || ordinal >= memberIds.size()) { throw new IllegalArgumentException("Unknown demo member ordinal " + ordinal); }
            return memberIds.get(ordinal);
        }
    }
    int order();
    /** Returns the created-item counts of this step. */
    Map<String, Integer> apply(Input input);
}
