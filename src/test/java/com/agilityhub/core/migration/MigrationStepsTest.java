package com.agilityhub.core.migration;

import com.agilityhub.core.migration.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class MigrationStepsTest {
    @Test void T_18_13_normalizesValidationAndSortsRangesWithoutMutableState() {
        var steps=MigrationSteps.parse("7-9,3,7");
        assertThat(steps.values()).containsExactly(1,3,7,8,9);
        assertThat(steps.includes(1)).isTrue(); assertThat(steps.includes(4)).isFalse();
        assertThatThrownBy(()->steps.values().add(4)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(MigrationExecutionRequest.full().steps()).isEqualTo(MigrationSteps.all());
        assertThat(MigrationExecutionRequest.full().staged()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"","0","11","3-2","1-11","1,","1,,3"," 1","-1","1.0","all"})
    void T_18_13_rejectsInvalidOrAmbiguousSelections(String value) {
        assertThatThrownBy(()->MigrationSteps.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void T_18_13_resumeRequiresExplicitStagedMode() {
        assertThatThrownBy(()->new MigrationExecutionRequest(MigrationSteps.all(),false,"prior",null)).isInstanceOf(IllegalArgumentException.class);
    }
}
