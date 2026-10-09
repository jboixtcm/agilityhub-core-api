package com.agilityhub.core.identity.application;

import java.util.List;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.identity.application.LearnImportReport.Outcome.*;
import static com.agilityhub.core.identity.application.LearnImportReport.Reason.*;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link LearnImportReport#render} (S01 R-01-12, T-01-14): the console summary says "Imported … created
 * … linked" for a real import and "Dry run … would create … would link" for a preview, with counts and reasons only.
 */
class LearnImportReportSurvivorsTest {
    static final List<LearnImportReport.Entry> ROWS = List.of(new LearnImportReport.Entry(2, CREATED, NEW_ACCOUNT),
            new LearnImportReport.Entry(3, LINKED, PASSWORD_ADOPTED), new LearnImportReport.Entry(4, SKIPPED, GUEST));

    @Test void T_01_14_aRealImportRendersWhatItCreatedAndLinked() {
        assertThat(LearnImportReport.of(false, ROWS).render()).isEqualTo(
                "Imported: 1 created, 1 linked, 1 skipped, 0 errors\nReasons: {NEW_ACCOUNT=1, PASSWORD_ADOPTED=1, GUEST=1}");
    }

    @Test void T_01_14_aDryRunRendersWhatItWouldCreateAndLink() {
        assertThat(LearnImportReport.of(true, ROWS).render()).isEqualTo(
                "Dry run: 1 would create, 1 would link, 1 skipped, 0 errors\nReasons: {NEW_ACCOUNT=1, PASSWORD_ADOPTED=1, GUEST=1}");
    }
}
