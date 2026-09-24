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
     * every census member id in ordinal order (E5-T06 scenario references), the club-local date of the run and whether
     * this is a `--reanchor` run (E5-T09: only the steps that {@link #reanchors()} run, over weeks that do not exist yet).
     * `generatedWeeks` is the run's hand-off between steps (E5-T14): the planning step (order 10) adds the week offsets
     * it generated itself, and a later step of a re-anchored run dates its items only into those weeks. */
    record Input(Map<String, Object> specification, long seed, LocalDate weekStart, String adminAccountId, Set<String> loginMemberIds,
            List<String> memberIds, LocalDate today, boolean reanchor, Set<Integer> generatedWeeks) {
        public Input {
            specification = Collections.unmodifiableMap(new LinkedHashMap<>(specification)); loginMemberIds = Set.copyOf(loginMemberIds);
            memberIds = List.copyOf(memberIds); generatedWeeks = Objects.requireNonNull(generatedWeeks);
        }
        public Input(Map<String, Object> specification, long seed, LocalDate weekStart, String adminAccountId, Set<String> loginMemberIds,
                List<String> memberIds, LocalDate today, boolean reanchor) {
            this(specification, seed, weekStart, adminAccountId, loginMemberIds, memberIds, today, reanchor, new TreeSet<>());
        }
        /** Whether a week offset exists because this run generated it (always true for the first run, which generates every week). */
        public boolean dated(int offset) { return !reanchor || generatedWeeks.contains(offset); }
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
    /** Whether the step takes part in a `seed:demo --reanchor` run (the planning weeks and their registrants). */
    default boolean reanchors() { return false; }
    /** Returns the created-item counts of this step. */
    Map<String, Integer> apply(Input input);
}
