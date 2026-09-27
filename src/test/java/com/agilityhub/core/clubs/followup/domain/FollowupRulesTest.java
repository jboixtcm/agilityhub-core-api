package com.agilityhub.core.clubs.followup.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** S10 R-10-13 unread formula and read-mark pruning (T-10-06) and the §3 excerpt, without I/O. */
class FollowupRulesTest {
    static final Instant READ_ALL = Instant.parse("2026-08-12T10:00:00Z");

    @Test void T_10_06_unreadIsNewerThanReadAllNotClickedAndNotMine() {
        var after = READ_ALL.plusSeconds(1);
        assertThat(FollowupRules.unread("f1", after, "estel", READ_ALL, List.of(), "marc")).isTrue();
        assertThat(FollowupRules.unread("f1", READ_ALL, "estel", READ_ALL, List.of(), "marc")).as("activityAt = readAllAt is read").isFalse();
        assertThat(FollowupRules.unread("f1", READ_ALL.minusSeconds(1), "estel", READ_ALL, List.of(), "marc")).isFalse();
        assertThat(FollowupRules.unread("f1", after, "estel", READ_ALL, List.of("f1"), "marc")).as("clicked").isFalse();
        assertThat(FollowupRules.unread("f1", after, "marc", READ_ALL, List.of(), "marc")).as("the author's own row").isFalse();
        assertThat(FollowupRules.unread("f1", READ_ALL.minusSeconds(3600), "estel", null, null, "marc")).as("no read mark yet").isTrue();
        assertThat(FollowupRules.unread("f1", null, "estel", null, List.of(), "marc")).isTrue();
        assertThat(FollowupRules.unread("f1", null, "estel", READ_ALL, List.of(), "marc")).isFalse();
        assertThat(FollowupRules.unread("f1", after, null, READ_ALL, List.of(), "marc")).isTrue();
    }

    @Test void T_10_06_pruningKeepsOnlyTheClicksReadAllDoesNotCover() {
        var activity = Map.of("old", READ_ALL.minusSeconds(60), "same", READ_ALL, "new", READ_ALL.plusSeconds(60));
        assertThat(FollowupRules.pruned(List.of("old", "same", "new", "new", "gone"), activity, READ_ALL)).containsExactly("new");
        assertThat(FollowupRules.pruned(List.of("old", "new", "gone"), activity, null)).as("no read-all: every existing row stays").containsExactly("old", "new");
        assertThat(FollowupRules.pruned(List.of(), activity, READ_ALL)).isEmpty();
    }

    @Test void T_10_18_excerptIsOneLineOfAtMost120CharactersAndNeverCutsACodePoint() {
        assertThat(FollowupRules.excerpt("  Practiqueu el balancí\n\n amb calma:\tsessions curtes  ")).isEqualTo("Practiqueu el balancí amb calma: sessions curtes");
        assertThat(FollowupRules.excerpt(null)).isEmpty();
        assertThat(FollowupRules.excerpt("")).isEmpty();
        String exact = "a".repeat(120);
        assertThat(FollowupRules.excerpt(exact)).isEqualTo(exact);
        String longer = "b".repeat(200);
        assertThat(FollowupRules.excerpt(longer)).hasSize(120).endsWith("…").startsWith("b".repeat(119));
        String emoji = "🐕".repeat(130);
        var cut = FollowupRules.excerpt(emoji);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(120);
        assertThat(cut).startsWith("🐕".repeat(119)).endsWith("…");
        assertThat(FollowupRules.excerpt("x".repeat(118) + "  " + "y".repeat(10))).as("no trailing space before «…»").isEqualTo("x".repeat(118) + "…");
    }
}
