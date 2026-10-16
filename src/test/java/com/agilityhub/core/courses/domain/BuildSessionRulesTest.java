package com.agilityhub.core.courses.domain;

import com.agilityhub.core.courses.domain.CourseTypes.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** S16 R-16-11: the build session's open statuses, its 12-hour life, the join code and the setup kind of a finished session. */
class BuildSessionRulesTest {
    static final Instant STARTED = Instant.parse("2026-10-05T08:00:00Z");

    @Test void T_16_10_onlyNotStartedAndInProgressAreOpen() {
        assertThat(BuildSessionRules.open(BuildStatus.NOT_STARTED)).isTrue();
        assertThat(BuildSessionRules.open(BuildStatus.IN_PROGRESS)).isTrue();
        assertThat(BuildSessionRules.open(BuildStatus.COMPLETED)).isFalse();
        assertThat(BuildSessionRules.open(BuildStatus.ABANDONED)).isFalse();
    }

    /** «Una sessió oberta > 12 h es tanca sola»: from 12 h after its start (else its creation), and never a closed one. */
    @Test void T_16_10_anOpenSessionIsOutlivedFromMaxHoursAfterItsStart() {
        Instant almost = STARTED.plus(Duration.ofHours(12)).minusSeconds(1), exactly = STARTED.plus(Duration.ofHours(12));
        assertThat(BuildSessionRules.outlived(BuildStatus.IN_PROGRESS, STARTED, null, 12, almost)).isFalse();
        assertThat(BuildSessionRules.outlived(BuildStatus.IN_PROGRESS, STARTED, null, 12, exactly)).isTrue();
        assertThat(BuildSessionRules.outlived(BuildStatus.NOT_STARTED, null, STARTED, 12, exactly)).isTrue();
        assertThat(BuildSessionRules.outlived(BuildStatus.NOT_STARTED, null, null, 12, exactly)).isFalse();
        assertThat(BuildSessionRules.outlived(BuildStatus.COMPLETED, STARTED, STARTED, 12, exactly.plusSeconds(3600))).isFalse();
        assertThat(BuildSessionRules.outlived(BuildStatus.IN_PROGRESS, STARTED, null, 2, STARTED.plus(Duration.ofHours(2)))).isTrue();
    }

    @Test void T_16_10_theJoinCodeHasSixUnambiguousCharactersAndIgnoresCaseAndBlanks() {
        var next = new AtomicInteger();
        String code = BuildSessionRules.joinCode(bound -> next.getAndIncrement() % bound);
        assertThat(code).isEqualTo("ABCDEF").hasSize(BuildSessionRules.CODE_LENGTH);
        assertThat(BuildSessionRules.joinCode(bound -> bound - 1)).isEqualTo("999999");
        assertThat(BuildSessionRules.CODE_ALPHABET).doesNotContain("0", "O", "1", "I", "L");
        assertThat(BuildSessionRules.normalizeCode("  abc9de ")).isEqualTo("ABC9DE");
        assertThat(BuildSessionRules.normalizeCode(null)).isEmpty();
    }

    /** R-16-11 «crea/actualitza el `RingSetup` (`kind` de la disciplina del recorregut)». */
    @Test void T_16_10_aFinishedSessionRegistersTheKindOfTheCoursesDiscipline() {
        assertThat(BuildSessionRules.kind(Discipline.AGILITY)).isEqualTo(SetupKind.AGILITY);
        assertThat(BuildSessionRules.kind(Discipline.JUMPING)).isEqualTo(SetupKind.JUMPING);
        assertThat(BuildSessionRules.kind(Discipline.OTHER)).isEqualTo(SetupKind.FUN);
    }
}
