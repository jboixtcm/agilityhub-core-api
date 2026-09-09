package com.agilityhub.core.clubs.census.domain;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CensusRulesTest {
    @Test void T_03_01_displayStatusPrecedenceAndClubDate() {
        var today = LocalDate.of(2026, 9, 15); var end = today.plusDays(15);
        assertThat(CensusRules.status("ACTIVE", null, end, null, today)).isEqualTo(new CensusRules.DisplayStatus("INACTIVE_PERIOD", end));
        assertThat(CensusRules.status("ACTIVE", end, null, null, today).kind()).isEqualTo("LEAVE_SCHEDULED");
        assertThat(CensusRules.status("ACTIVE", today.minusDays(1), today.minusDays(1), null, today).kind()).isEqualTo("ACTIVE");
        assertThat(CensusRules.status("LEFT", end, null, null, today).date()).isEqualTo(end);
        assertThat(CensusRules.status("PENDING", null, null, null, today).kind()).isEqualTo("PENDING");
        assertThat(CensusRules.status("ACTIVE", null, null, Instant.EPOCH, today).kind()).isEqualTo("ERASED");
    }
    @Test void T_03_02_freeTrainingOverrideAndDisabledLevels() {
        assertThat(FreeTrainingPolicy.evaluate(null, true, true)).isEqualTo(new FreeTrainingPolicy.Result(true, "LEVEL", null));
        assertThat(FreeTrainingPolicy.evaluate(false, true, true)).isEqualTo(new FreeTrainingPolicy.Result(false, "MANUAL", false));
        assertThat(FreeTrainingPolicy.evaluate(null, false, true).allowed()).isFalse();
        assertThat(FreeTrainingPolicy.evaluate(null, true, false).allowed()).isFalse();
        assertThat(FreeTrainingPolicy.evaluate(true, false, false).allowed()).isTrue();
    }
    @Test void T_03_03_masksNeverExposeCompleteBankOrIdentityValues() {
        assertThat(CensusRules.maskedIban("ES91 2100 0418 4502 0005 1332")).isEqualTo("···· ···· ···· ···· 1332");
        assertThat(CensusRules.maskedIban(null)).isNull(); assertThat(CensusRules.maskedIban(" ")).isNull();
        assertThat(CensusRules.maskedId("381234561P")).isEqualTo("38······1P");
        assertThat(CensusRules.maskedId(null)).isEqualTo("······"); assertThat(CensusRules.maskedId("123")).isEqualTo("······");
    }
    @Test void T_03_04_completeYearsAtEachClubLocalDate() {
        var time = Instant.parse("2026-09-03T00:30:00Z"); var birth = LocalDate.parse("2022-03-12");
        assertThat(CensusRules.age(birth, time.atZone(ZoneId.of("Europe/Madrid")).toLocalDate())).isEqualTo(4);
        assertThat(CensusRules.age(birth, time.atZone(ZoneId.of("America/Argentina/Buenos_Aires")).toLocalDate())).isEqualTo(4);
        assertThat(CensusRules.age(LocalDate.parse("2022-09-03"), time.atZone(ZoneId.of("America/Argentina/Buenos_Aires")).toLocalDate())).isEqualTo(3);
        assertThat(CensusRules.age(null, birth)).isZero();
        var joined = Instant.parse("2023-12-31T23:30:00Z");
        assertThat(joined.atZone(ZoneId.of("Europe/Madrid")).getYear()).isEqualTo(2024);
        assertThat(joined.atZone(ZoneId.of("America/Argentina/Buenos_Aires")).getYear()).isEqualTo(2023);
    }
    @Test void T_03_06_onlyEffectiveLifecycleTransitionsAreAllowed() {
        CensusRules.transition("PENDING", "ACTIVE", null); CensusRules.transition("ACTIVE", "LEFT", null);
        CensusRules.transition("LEFT", "ACTIVE", null); CensusRules.transition("INACTIVE", "ACTIVE", null);
        CensusRules.transition("PENDING", "LEFT", "Rejected");
        for (var state : new String[][]{{"LEFT", "PENDING"}, {"PENDING", "LEFT"}, {"ACTIVE", "PENDING"}, {"ERASED", "ACTIVE"}, {"PENDING", "PENDING"}}) {
            assertThatThrownBy(() -> CensusRules.transition(state[0], state[1], null)).hasMessage("INVALID_STATE");
        }
        assertThatThrownBy(() -> CensusRules.transition("PENDING", "LEFT", " ")).hasMessage("INVALID_STATE");
        CensusRules.mutable(null); assertThatThrownBy(() -> CensusRules.mutable(Instant.EPOCH)).hasMessage("MEMBER_ERASED");
    }
}
